package dev.dsh.nativeapp;

import java.io.File;
import java.util.List;

/** WorkspaceProjects 的离线回归测试。 */
public class WorkspaceProjectsTest {
    static int pass, fail;
    static void check(String name, boolean ok, String detail) {
        if (ok) pass++; else { fail++; System.out.println("  FAIL " + name + " -> " + detail); }
    }
    public static void main(String[] args) throws Exception {
        File root = new File("/tmp/dsh-projects-test"); rmrf(root); root.mkdirs();
        check("null becomes default", "".equals(WorkspaceProjects.normalize(null)), "wrong");
        check("whitespace becomes default", "".equals(WorkspaceProjects.normalize("  ")), "wrong");
        check("Chinese kept", "语文资料".equals(WorkspaceProjects.normalize(" 语文资料 ")), "wrong");
        check("spaces collapsed", "高三 四班".equals(WorkspaceProjects.normalize("高三   四班")), "wrong");
        check("slash rejected", WorkspaceProjects.normalize("a/b") == null, "accepted");
        check("backslash rejected", WorkspaceProjects.normalize("a\\b") == null, "accepted");
        check("parent rejected", WorkspaceProjects.normalize("..") == null, "accepted");
        check("hidden rejected", WorkspaceProjects.normalize(".secret") == null, "accepted");
        check("long rejected", WorkspaceProjects.normalize(repeat("a", 49)) == null, "accepted");
        check("default uses root", WorkspaceProjects.directory(root, "").equals(root), "wrong");
        check("project created", WorkspaceProjects.create(root, "项目 A"), "failed");
        check("second project created", WorkspaceProjects.create(root, "项目 B"), "failed");
        check("duplicate is safe", WorkspaceProjects.create(root, "项目 A"), "failed");
        File a = WorkspaceProjects.directory(root, "项目 A");
        check("project contained", WorkspaceProjects.contained(root, a), String.valueOf(a));
        check("outside rejected", !WorkspaceProjects.contained(root, new File(root, "../outside")), "accepted");
        List<String> names = WorkspaceProjects.list(root);
        check("two projects listed", names.size() == 2, names.toString());
        check("sorted", "项目 A".equals(names.get(0)), names.toString());
        check("default label", "默认工作区".equals(WorkspaceProjects.displayName("")), "wrong");
        check("named label", "项目 A".equals(WorkspaceProjects.displayName("项目 A")), "wrong");
        rmrf(root);
        System.out.println("TOTAL: " + pass + " pass / " + fail + " fail");
        if (fail > 0) System.exit(1);
    }
    static String repeat(String s, int n) { StringBuilder b=new StringBuilder(); for(int i=0;i<n;i++)b.append(s); return b.toString(); }
    static void rmrf(File f) { if(f.isDirectory()){File[] c=f.listFiles();if(c!=null)for(File x:c)rmrf(x);}f.delete(); }
}
