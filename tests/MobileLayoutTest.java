package dev.dsh.nativeapp;

/** MobileLayout 的离线回归测试。 */
public class MobileLayoutTest {
    static int pass, fail;
    static void check(String name, boolean ok, String detail) {
        if (ok) pass++; else { fail++; System.out.println("  FAIL " + name + " -> " + detail); }
    }
    public static void main(String[] args) {
        if (args.length == 3 && "--dump-html".equals(args[0])) {
            try {
                String html = new String(java.nio.file.Files.readAllBytes(
                        java.nio.file.Paths.get(args[1])), java.nio.charset.StandardCharsets.UTF_8);
                String patched = MobileLayout.patchHtml(html, Integer.parseInt(args[2]));
                if (patched == null) throw new IllegalArgumentException("unsupported HTML");
                System.out.print(patched);
                return;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
        // 保留用户已确认正常的 480 CSS px 基线，不用缩放改变图标几何。
        check("narrow phone keeps 480", MobileLayout.viewportWidth(400) == 480, "wrong");
        check("real-world 436dp keeps 480", MobileLayout.viewportWidth(436) == 480,
                "would enlarge every element");
        check("very narrow phone not shrunk", MobileLayout.viewportWidth(320) == 480, "wrong");
        check("wide phone uses real width", MobileLayout.viewportWidth(600) == 600, "wrong");
        check("landscape not multiplied", MobileLayout.viewportWidth(869) == 869, "wrong");
        check("huge width capped", MobileLayout.viewportWidth(2000) == 1440, "wrong");
        check("invalid width falls back to 480", MobileLayout.viewportWidth(0) == 480, "wrong");
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
                && !once.contains("min-height:44px"), "would stretch icon controls");
        check("narrow settings use vertical layout", once != null
                && once.contains("max-width:520px")
                && once.contains("flex-direction:column!important"), "missing");
        check("no guessed module class selectors", once != null
                && !once.contains("[class*="), "guessed class matches survived");
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
        // 全局按钮覆盖曾破坏设置；后续布局补丁必须限定到实测的稳定锚点。
        String flat = once == null ? "" : once.replaceAll("\\s+", "");
        check("no global button layout override",
                !flat.contains("button{flex-shrink:0!important;}")
                        && !flat.contains("button{flex-shrink:0}"),
                "global button rule would break upstream components");
        check("no global button min-width override",
                !flat.contains("button[aria-label]{min-width:0"),
                "global aria-label button rule is too broad");
        check("media still bounded",
                flat.contains("img,svg{max-width:100%"),
                "missing media rule");
        check("dialogs still bounded",
                flat.contains("[role=dialog]"),
                "missing dialog rule");
        check("settings layout rules intact",
                flat.contains("[data-shortcut-modal=\"settings\"]"),
                "settings-specific rules were lost");

        System.out.println("TOTAL: " + pass + " pass / " + fail + " fail");
        if (fail > 0) System.exit(1);
    }
}
