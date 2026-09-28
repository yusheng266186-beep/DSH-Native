package dev.dsh.nativeapp;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class FileBatchTest {
    static int pass, fail;
    static void check(String what, boolean ok, String detail) { if(ok){pass++;System.out.println("  OK   "+what);}else{fail++;System.out.println("  FAIL "+what+" -> "+detail);} }
    public static void main(String[] args) throws Exception {
        File base=new File("/tmp/filebatchtest");rmrf(base);File root=new File(base,"root"),dest=new File(root,"dest"),outside=new File(base,"outside");dest.mkdirs();outside.mkdirs();List<File> roots=new ArrayList<File>();roots.add(root);
        write(new File(root,"a.txt"),"a");File dir=new File(root,"folder");dir.mkdirs();write(new File(dir,"b.txt"),"b");
        FileBatch.Result copy=FileBatch.transfer(Arrays.asList(new File(root,"a.txt"),dir),dest,false,roots);
        check("copies selection",copy.succeeded==2&&copy.failed==0,copy.errors.toString());check("keeps source",new File(root,"a.txt").isFile(),"gone");check("recursive copy",new File(dest,"folder/b.txt").isFile(),"missing");
        FileBatch.Result duplicate=FileBatch.transfer(Arrays.asList(new File(root,"a.txt")),dest,false,roots);check("collision suffix",duplicate.succeeded==1&&new File(dest,"a (1).txt").isFile(),duplicate.errors.toString());
        write(new File(root,"move.txt"),"move");FileBatch.Result move=FileBatch.transfer(Arrays.asList(new File(root,"move.txt")),dest,true,roots);check("move succeeds",move.succeeded==1&&!new File(root,"move.txt").exists()&&new File(dest,"move.txt").isFile(),move.errors.toString());
        write(new File(outside,"x.txt"),"x");check("outside source rejected",FileBatch.transfer(Arrays.asList(new File(outside,"x.txt")),dest,false,roots).failed==1,"allowed");check("outside destination rejected",FileBatch.transfer(Arrays.asList(new File(root,"a.txt")),outside,false,roots).failed==1,"allowed");
        File child=new File(dir,"child");child.mkdirs();check("self copy rejected",FileBatch.transfer(Arrays.asList(dir),child,false,roots).failed==1,"allowed");
        File link=new File(root,"link");Files.createSymbolicLink(link.toPath(),new File(root,"a.txt").toPath());check("symlink rejected",FileBatch.transfer(Arrays.asList(link),dest,false,roots).failed==1,"allowed");check("empty selection rejected",FileBatch.transfer(new ArrayList<File>(),dest,false,roots).failed==1,"allowed");
        rmrf(base);System.out.println("TOTAL: "+pass+" pass / "+fail+" fail");if(fail>0)System.exit(1);
    }
    static void write(File f,String s)throws Exception{File p=f.getParentFile();if(p!=null)p.mkdirs();java.io.FileOutputStream o=new java.io.FileOutputStream(f);o.write(s.getBytes("UTF-8"));o.close();}
    static void rmrf(File f){if(f.isDirectory()&&!Files.isSymbolicLink(f.toPath())){File[]k=f.listFiles();if(k!=null)for(File x:k)rmrf(x);}f.delete();}
}
