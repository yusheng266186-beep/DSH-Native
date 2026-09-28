package dev.dsh.nativeapp;

public class SessionOrganizerTest {
    static int pass, fail;
    static void check(String what, boolean ok) { if(ok)pass++;else{fail++;System.out.println("  FAIL "+what);} }
    public static void main(String[] args) {
        if (args.length > 0 && "--dump-search".equals(args[0])) {
            System.out.print(SessionOrganizer.focusSearchScript()); return;
        }
        if (args.length > 0 && "--dump-archive".equals(args[0])) {
            System.out.print(SessionOrganizer.showArchivedScript()); return;
        }
        String search=SessionOrganizer.focusSearchScript(), archive=SessionOrganizer.showArchivedScript();
        check("search labels",search.contains("搜索会话名称")&&search.contains("search session names"));
        check("search expands",search.contains(".click()")&&search.contains("setTimeout"));
        check("search focuses",search.contains(".focus()"));
        check("archive filter",archive.contains("筛选会话")&&archive.contains("Filter sessions"));
        check("archive choice",archive.contains("仅显示已归档")&&archive.contains("Archived only"));
        check("no fetch",!search.contains("fetch(")&&!archive.contains("fetch("));
        check("no native bridge",!search.contains("JavascriptInterface")&&!archive.contains("JavascriptInterface"));
        check("markers",search.contains(SessionOrganizer.SEARCH_READY)&&archive.contains(SessionOrganizer.ARCHIVE_READY));
        check("unsupported parser",SessionOrganizer.isUnsupported("x "+SessionOrganizer.UNSUPPORTED));
        check("search parser",SessionOrganizer.isSearchReady(SessionOrganizer.SEARCH_READY));
        check("archive parser",SessionOrganizer.isArchiveReady(SessionOrganizer.ARCHIVE_READY));
        check("null safe",!SessionOrganizer.isUnsupported(null));
        System.out.println("TOTAL: "+pass+" pass / "+fail+" fail");if(fail>0)System.exit(1);
    }
}
