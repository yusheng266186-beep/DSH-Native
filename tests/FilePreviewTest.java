package dev.dsh.nativeapp;

public class FilePreviewTest {
    static int pass, fail;
    static void check(String what, boolean ok) { if(ok)pass++;else{fail++;System.out.println("  FAIL "+what);} }
    public static void main(String[] args) {
        check("png image",FilePreview.kind("A.PNG")==FilePreview.IMAGE);
        check("webp image",FilePreview.kind("x.webp")==FilePreview.IMAGE);
        check("markdown text",FilePreview.kind("README.md")==FilePreview.TEXT);
        check("yaml text",FilePreview.kind("a.yaml")==FilePreview.TEXT);
        check("apk other",FilePreview.kind("a.apk")==FilePreview.OTHER);
        check("null other",FilePreview.kind(null)==FilePreview.OTHER);
        check("image positive",FilePreview.canDecodeImage(1));
        check("image zero rejected",!FilePreview.canDecodeImage(0));
        check("image cap accepted",FilePreview.canDecodeImage(FilePreview.MAX_IMAGE_BYTES));
        check("image too large",!FilePreview.canDecodeImage(FilePreview.MAX_IMAGE_BYTES+1));
        System.out.println("TOTAL: "+pass+" pass / "+fail+" fail");if(fail>0)System.exit(1);
    }
}
