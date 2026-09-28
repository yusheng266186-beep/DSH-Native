package dev.dsh.nativeapp;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

public class FileTrashTest {
    static int pass, fail;
    static void check(String what, boolean ok, String detail) { if(ok){pass++;System.out.println("  OK   "+what);}else{fail++;System.out.println("  FAIL "+what+" -> "+detail);} }
    public static void main(String[] args) throws Exception {
        File base=new File("/tmp/filetrashtest");rmrf(base);File root=new File(base,"root");root.mkdirs();File outside=new File(base,"outside");outside.mkdirs();List<File> roots=new ArrayList<File>();roots.add(root);
        File one=new File(root,"one.txt");write(one,"one");check("move to trash",FileTrash.move(one,roots)==null,"failed");check("source removed",!one.exists(),"exists");List<FileTrash.Entry> list=FileTrash.list(roots);check("record listed",list.size()==1&&"one.txt".equals(list.get(0).name()),String.valueOf(list.size()));check("restore",FileTrash.restore(list.get(0),roots)==null,"failed");check("content restored",one.isFile()&&"one".equals(read(one)),"wrong");
        File collision=new File(root,"same.txt");write(collision,"old");check("trash collision",FileTrash.move(collision,roots)==null,"failed");write(collision,"new");FileTrash.Entry saved=FileTrash.list(roots).get(0);check("restore collision",FileTrash.restore(saved,roots)==null,"failed");check("does not overwrite","new".equals(read(collision))&&"old".equals(read(new File(root,"same (1).txt"))),"wrong");
        File folder=new File(root,"folder");folder.mkdirs();write(new File(folder,"nested.txt"),"nested");check("trash directory",FileTrash.move(folder,roots)==null,"failed");List<FileTrash.Entry> remaining=FileTrash.list(roots);check("directory metadata",remaining.size()==1&&remaining.get(0).directory,String.valueOf(remaining.size()));check("purge",FileTrash.purge(remaining.get(0),roots)==null&&FileTrash.list(roots).isEmpty(),"failed");
        File out=new File(outside,"x.txt");write(out,"x");check("outside rejected",FileTrash.move(out,roots)!=null,"allowed");check("root rejected",FileTrash.move(root,roots)!=null,"allowed");File bad=new File(new File(new File(root,FileTrash.DIR),"meta"),"bad.bin");write(bad,"bad");check("corrupt metadata ignored",FileTrash.list(roots).isEmpty(),"listed");
        rmrf(base);System.out.println("TOTAL: "+pass+" pass / "+fail+" fail");if(fail>0)System.exit(1);
    }
    static void write(File f,String s)throws Exception{File p=f.getParentFile();if(p!=null)p.mkdirs();java.io.FileOutputStream o=new java.io.FileOutputStream(f);o.write(s.getBytes("UTF-8"));o.close();}
    static String read(File f)throws Exception{return new String(Files.readAllBytes(f.toPath()),"UTF-8");}
    static void rmrf(File f){if(f.isDirectory()&&!Files.isSymbolicLink(f.toPath())){File[]k=f.listFiles();if(k!=null)for(File x:k)rmrf(x);}f.delete();}
}
