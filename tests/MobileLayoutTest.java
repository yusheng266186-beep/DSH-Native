package dev.dsh.nativeapp;

/** MobileLayout 的离线回归测试。 */
public class MobileLayoutTest {
    static int pass, fail;
    static void check(String name, boolean ok, String detail) {
        if (ok) pass++; else { fail++; System.out.println("  FAIL " + name + " -> " + detail); }
    }
    public static void main(String[] args) {
        // 视口必须**永不宽于屏幕**：一台 436dp 的手机拿到 480 CSS px 的视口，
        // 页面比屏幕宽 10%，每个 flex 容器都在压缩（附件 chip 的文字盖住 ×
        // 按钮、模型选择器尾字被切）。撑大视口救不了组件，只会把挤压摊开。
        check("narrow phone uses real width", MobileLayout.viewportWidth(400) == 400, "wrong");
        check("viewport never exceeds screen",
                MobileLayout.viewportWidth(436) == 436, "wider than screen");
        check("very narrow phone not inflated",
                MobileLayout.viewportWidth(320) == 320, "wrong");
        check("wide phone uses real width", MobileLayout.viewportWidth(600) == 600, "wrong");
        check("landscape not multiplied", MobileLayout.viewportWidth(869) == 869, "wrong");
        check("huge width capped", MobileLayout.viewportWidth(2000) == 1440, "wrong");
        check("invalid width falls back", MobileLayout.viewportWidth(0) == 400, "wrong");
        String html = "<html><head><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"></head><body></body></html>";
        String once = MobileLayout.patchHtml(html, 480);
        check("viewport patched", once != null && once.contains("width=480"), String.valueOf(once));
        check("viewport no longer forces 1:1 scale", once != null
                && !once.contains("width=480, initial-scale=1"), String.valueOf(once));
        check("style injected", once != null && once.contains("dsh-native-responsive"), "missing");
        check("session probe embedded before app modules", once != null
                && once.contains("dsh-native-session-probe")
                && once.indexOf("dsh-native-session-probe") < once.indexOf("</head>"), "missing");
        check("connection watcher embedded early", once != null
                && once.contains("dsh-native-connection-watch")
                && once.indexOf("dsh-native-connection-watch") < once.indexOf("</head>"), "missing");
        check("draft recovery embedded early", once != null
                && once.contains("dsh-native-draft-recovery")
                && once.indexOf("dsh-native-draft-recovery") < once.indexOf("</head>"), "missing");
        check("settings switches keep native size", once != null
                && once.contains("[role=switch]{flex-shrink:0!important;}"), "missing");
        check("global button height override removed", once != null
                && !once.contains("button,[role=button]{min-height:44px;}"), "still present");
        check("narrow settings use vertical layout", once != null
                && once.contains("max-width:520px")
                && once.contains("flex-direction:column!important"), "missing");
        check("coarse pointer touch targets", once != null
                && once.contains("@media(pointer:coarse)")
                && once.contains("button:not([role=switch])"), "missing");
        check("switch excluded from touch override", once != null
                && once.contains(":not([role=switch])"), "missing");
        check("reduced motion respected", once != null
                && once.contains("prefers-reduced-motion:reduce")
                && once.contains("transition-duration:.01ms"), "missing");
        check("dialog actions may wrap safely", once != null
                && once.contains("overflow-wrap:anywhere"), "missing");
        String twice = MobileLayout.patchHtml(once, 869);
        check("patch idempotent", twice != null && twice.indexOf("dsh-native-responsive")
                == twice.lastIndexOf("dsh-native-responsive"), String.valueOf(twice));
        check("probe idempotent", twice != null && twice.indexOf("dsh-native-session-probe")
                == twice.lastIndexOf("dsh-native-session-probe"), String.valueOf(twice));
        check("connection watcher idempotent", twice != null
                && twice.indexOf("dsh-native-connection-watch")
                == twice.lastIndexOf("dsh-native-connection-watch"), String.valueOf(twice));
        check("draft recovery idempotent", twice != null
                && twice.indexOf("dsh-native-draft-recovery")
                == twice.lastIndexOf("dsh-native-draft-recovery"), String.valueOf(twice));
        check("width can change", twice != null && twice.contains("width=869"), String.valueOf(twice));
        String oldScale = "<html><head><meta name=\"viewport\" "
                + "content=\"width=480, initial-scale=0.8333\"></head></html>";
        String migrated = MobileLayout.patchHtml(oldScale, 600);
        check("older calculated scale is migrated", migrated != null
                && migrated.contains("content=\"width=600\""), String.valueOf(migrated));
        check("missing viewport rejected", MobileLayout.patchHtml("<head></head>", 480) == null, "accepted");
        // 编辑器区域的窄屏规则：附件 × 按钮曾被缩略图挤变形、文件名盖住取消按钮。
        // 这些节点没有 data-* 锚点，类名又是 CSS Module 哈希化的，所以规则用的是
        // 关键约束：规则**不得依赖 CSS Module 哈希类名**。
        //
        // 实测编译后的类名形如 Di.close / Ee.itemIcon，不含 remove / thumbnail
        // 等语义词 —— 早先按源码变量名写的 [class*=remove]、[class*=thumbnail]
        // 在真实页面上一个都匹配不到，而构建日志照样显示「已写入」，
        // 于是「按钮被挤压」一直没解决却看起来一切正常。
        String flat = once == null ? "" : once.replaceAll("\\s+", "");
        check("no class-name selectors in responsive css",
                flat.indexOf("[class*=") < 0,
                "rules still depend on hashed CSS-module class names");
        check("every button is unshrinkable",
                flat.contains("button{flex-shrink:0!important;}"),
                "missing global button flex-shrink guard");
        check("icon buttons get a size floor",
                flat.contains("button[aria-label]{min-width:0;}"),
                "missing aria-label button rule");
        check("media never exceeds container",
                flat.contains("img,svg{max-width:100%"),
                "missing media rule");
        check("dialog buttons stay bounded",
                flat.contains("[role=dialog][role=button]"), "missing dialog button rule");

        System.out.println("TOTAL: " + pass + " pass / " + fail + " fail");
        if (fail > 0) System.exit(1);
    }
}
