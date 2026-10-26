#!/usr/bin/env python3
"""Production file intents and ContentProvider against deterministic Android API boundaries."""
import importlib.util,pathlib,shutil,subprocess,tempfile
ROOT=pathlib.Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('boundaries',ROOT/'tests/check_native_bridge.py');module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
STUBS={key:module.STUBS[key] for key in ['android/net/Uri.java','android/content/Intent.java','android/content/ClipData.java']}
STUBS['android/net/Uri.java']='''package android.net;public class Uri {public String value;public static Uri parse(String value){Uri uri=new Uri();uri.value=value;return uri;}public String getAuthority(){return java.net.URI.create(value).getAuthority();}public String getEncodedPath(){return java.net.URI.create(value).getRawPath();}}'''
STUBS['android/content/Intent.java']=STUBS['android/content/Intent.java'].replace('ACTION_SEND="send"','ACTION_EDIT="edit",ACTION_SEND="send"').replace('FLAG_GRANT_READ_URI_PERMISSION=1','FLAG_GRANT_READ_URI_PERMISSION=1,FLAG_GRANT_WRITE_URI_PERMISSION=2')
STUBS.update({
'android/content/Context.java':'''package android.content;import java.io.File;public class Context {public File files,cache;public File getFilesDir(){return files;}public File getCacheDir(){return cache;}}''',
'android/app/Activity.java':'''package android.app;import android.content.*;public class Activity extends Context {public Intent lastIntent;public void startActivity(Intent intent){lastIntent=intent;}}''',
'android/content/ContentProvider.java':'''package android.content;import android.net.Uri;import android.database.Cursor;import android.os.ParcelFileDescriptor;public abstract class ContentProvider {public Context context;public Context getContext(){return context;}public abstract boolean onCreate();public abstract ParcelFileDescriptor openFile(Uri uri,String mode)throws java.io.FileNotFoundException;public abstract String getType(Uri uri);public abstract Cursor query(Uri uri,String[] projection,String selection,String[] args,String sort);public abstract int delete(Uri uri,String s,String[] args);public abstract int update(Uri uri,ContentValues v,String s,String[] args);}''',
'android/content/ContentValues.java':'package android.content;public class ContentValues {}',
'android/database/Cursor.java':'package android.database;public interface Cursor {}',
'android/database/MatrixCursor.java':'package android.database;public class MatrixCursor implements Cursor {public MatrixCursor(String[] fields){}public void addRow(Object[] row){}}',
'android/provider/OpenableColumns.java':'package android.provider;public class OpenableColumns {public static final String DISPLAY_NAME="name",SIZE="size";}',
'android/os/ParcelFileDescriptor.java':'''package android.os;import java.io.*;public class ParcelFileDescriptor {public static final int MODE_READ_ONLY=1;public File file;public int mode;public static int parseMode(String mode){return mode.equals("r")?MODE_READ_ONLY:2;}public static ParcelFileDescriptor open(File file,int mode)throws FileNotFoundException {if(!file.isFile())throw new FileNotFoundException();ParcelFileDescriptor out=new ParcelFileDescriptor();out.file=file;out.mode=mode;return out;}}''',
})
with tempfile.TemporaryDirectory(prefix='dsh-file-actions-') as directory:
 root=pathlib.Path(directory)
 for name,text in STUBS.items():
  p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text)
 compiler=[shutil.which('javac')] if shutil.which('javac') else ['java','-m','jdk.compiler/com.sun.tools.javac.Main']
 sources=[module.SOURCE/(name+'.java') for name in ['FileOps','ShareTargets','ProviderRoute','UiText','ExternalFileActions','UpdateProvider']]
 subprocess.run([*compiler,'-encoding','UTF-8','-d',str(root/'classes'),*map(str,[*root.rglob('*.java'),*sources,ROOT/'tests/FileActionsAndroidTest.java'])],check=True)
 subprocess.run(['java','-cp',str(root/'classes'),'dev.dsh.nativeapp.FileActionsAndroidTest'],check=True)
