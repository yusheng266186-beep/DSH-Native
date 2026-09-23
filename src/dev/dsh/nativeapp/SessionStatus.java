package dev.dsh.nativeapp;

/**
 * 通知栏状态看板的判定逻辑：**纯 Java，无 Android 依赖，可离线测试**。
 *
 * <h3>要解决的问题</h3>
 * 用户希望「下拉通知栏就能看到当前项目在干什么」：是否在运行、是否结束、
 * 是否需要自己批准、网络有没有问题。
 *
 * <h3>状态从哪里来</h3>
 * 最初的想法是轮询 DSH 的 HTTP 接口（{@code /api/session/list}）——
 * <b>实测这个接口不存在</b>，DSH 的服务端 API 走的是自定义 RPC
 * （WebSocket + {@code /api/remote.mux}），不是 REST。
 * 所以那个版本的通知功能实际上从未生效过。
 *
 * <p>现在改为读**页面自身的状态**：DSH 的界面会把这些状态渲染出来，
 * 而三个关键的文案是稳定的（取自 DSH 客户端插件的 locale 字典）：
 *
 * <table>
 *   <tr><td>{@code input.stop}</td><td>停止生成</td><td>存在 → 正在运行</td></tr>
 *   <tr><td>{@code input.send}</td><td>发送消息</td><td>存在 → 空闲可发送</td></tr>
 *   <tr><td>{@code waiting}</td><td>等待审批</td><td>存在 → 需要用户批准</td></tr>
 * </table>
 *
 * <p>判定逻辑单独抽出来，是因为它全是边界情况：按钮都找不到怎么办、
 * 页面没加载完怎么办、状态抖动怎么办 —— 这些必须能测。
 */
final class SessionStatus {

    /** 空闲：没有任务在跑。 */
    static final int IDLE = 0;
    /** 运行中：agent 正在工作。 */
    static final int RUNNING = 1;
    /** 等待用户的批准。 */
    static final int AWAITING_APPROVAL = 2;
    /** 状态未知：页面没加载完，或找不到任何判定依据。 */
    static final int UNKNOWN = 3;

    private SessionStatus() { }

    /**
     * 从页面快照判定状态。
     *
     * <p>优先级：**等待批准 > 运行中 > 空闲**。
     * 因为「等待批准」本身就是一种运行中状态（任务卡在那里等用户），
     * 而它对用户的紧迫性更高 —— 通知必须把这件事说清楚。
     *
     * @param stopButton      页面上有「停止生成」按钮
     * @param sendButton      页面上有「发送消息」按钮
     * @param approvalPrompt  页面上有「等待审批」
     * @param pageReady       页面已加载完成（否则一律 UNKNOWN）
     */
    static int fromDom(boolean stopButton, boolean sendButton,
                       boolean approvalPrompt, boolean pageReady) {
        if (!pageReady) return UNKNOWN;
        // 等待批准优先：任务其实在跑，只是卡在用户这一步
        if (approvalPrompt) return AWAITING_APPROVAL;
        if (stopButton) return RUNNING;
        if (sendButton) return IDLE;
        // 两个按钮都没找到：可能是页面在切换，或 DSH 改了文案。
        // 报 UNKNOWN 而不是猜一个 —— 通知里显示错误的状态比不显示更糟。
        return UNKNOWN;
    }

    /** 状态的中文名。 */
    static String label(int state) {
        switch (state) {
            case RUNNING:           return "运行中";
            case AWAITING_APPROVAL: return "等待批准";
            case IDLE:              return "空闲";
            default:                return "状态未知";
        }
    }

    /**
     * 通知的标题。
     *
     * <p>标题里带上运行时长：用户下拉时最想知道的是「跑了多久了」。
     */
    static String title(int state, long runningMs) {
        // 不再把时长拼进标题。
        //
        // 原来每次推送都带一个当时算出来的秒数，而推送是每 2 秒一次 ——
        // 于是通知里的秒数两秒两秒地跳。
        // 现在改用系统计时器（setUsesChronometer + setWhen），
        // 由系统每秒自己走，与轮询周期完全无关。
        return "DeepSeek Harness · " + label(state);
    }

    /** 是否用系统计时器显示运行时长。 */
    static boolean useChronometer(int state) {
        return state == RUNNING || state == AWAITING_APPROVAL;
    }

    /**
     * 通知的正文。
     *
     * <p>网络异常时**必须**顶到正文里 —— 用户看到「运行中」却一直没动静，
     * 最常见的原因就是网断了，不说的话只会以为是 agent 卡住。
     */
    static String text(int state, boolean networkOk, String networkDetail) {
        StringBuilder sb = new StringBuilder();
        if (!networkOk) {
            sb.append("网络不可用");
            if (networkDetail != null && networkDetail.length() > 0) {
                sb.append("（").append(networkDetail).append("）");
            }
            sb.append("　");
        }
        switch (state) {
            case RUNNING:
                sb.append(networkOk ? "正在执行任务" : "任务可能已中断");
                break;
            case AWAITING_APPROVAL:
                sb.append("需要你的批准，点开处理");
                break;
            case IDLE:
                sb.append(networkOk ? "当前没有任务在运行" : "网络恢复后可直接继续");
                break;
            default:
                sb.append("正在获取状态…");
                break;
        }
        return sb.toString();
    }

    /**
     * 通知的重要性。
     *
     * <p>需要批准时用高优先级（横幅提示），其余保持低优先级 ——
     * 常驻通知如果一直打扰用户，会被直接关掉，那就什么都看不到了。
     */
    static int importance(int state, boolean networkOk) {
        if (state == AWAITING_APPROVAL) return 3;   // IMPORTANCE_HIGH
        if (!networkOk) return 2;                   // IMPORTANCE_DEFAULT
        return 1;                                   // IMPORTANCE_LOW
    }

    /**
     * 是否需要发出提示音/震动。
     *
     * <p>只在「等待批准」时提醒 —— 那是真的需要用户动作。
     * 运行中/空闲的变化不打扰。
     */
    static boolean shouldAlert(int previous, int current) {
        return current == AWAITING_APPROVAL && previous != AWAITING_APPROVAL;
    }

    /** 运行时长，形如 {@code 2:14}；不足一秒返回空串。 */
    static String duration(long ms) {
        if (ms <= 0) return "";
        long sec = ms / 1000;
        if (sec <= 0) return "";
        if (sec < 60) return sec + " 秒";
        long min = sec / 60;
        long s = sec % 60;
        if (min < 60) return min + ":" + (s < 10 ? "0" : "") + s;
        long h = min / 60;
        return h + ":" + ((min % 60) < 10 ? "0" : "") + (min % 60);
    }

    /** 网络状态的可读描述。 */
    static String networkLabel(boolean connected, boolean wifi, boolean cellular,
                               boolean validated) {
        if (!connected) return "未连接";
        if (!validated) return "已连接但无法访问外网";
        if (wifi) return "Wi-Fi";
        if (cellular) return "移动数据";
        return "已连接";
    }

    /**
     * 注入到页面的状态采集脚本。
     *
     * <p>三个文案取自 DSH 客户端插件的 locale 字典（中英文都给上，
     * 因为用户可能切换语言）：
     * <pre>
     *   dsh-client-ui-conversation  input.stop = "停止生成" / "Stop generating"
     *                               input.send = "发送消息" / "Send message"
     *   dsh-client-ui-approval      waiting    = "等待审批" / "Waiting for approval"
     * </pre>
     *
     * <p>用 {@code textContent} 而不是 {@code innerText}：后者会触发布局计算，
     * 每两秒跑一次不划算。
     *
     * <p>只上报**状态变化**，不是每次都报 —— 否则控制台会被刷爆。
     */
    static String pollScript() {
        // 判据必须**精确匹配**，不能用子串。
        //
        // 踩过的坑：原来用 indexOf 在整页文字里找「等待审批」，
        // 结果用户跟 agent 聊到这个功能时，对话正文本身就含这四个字，
        // 于是正常运行时也报「等待批准」。
        //
        // 第二个坑：只看**当前视图**的输入框。用户点进子代理的会话后，
        // 看到的是子代理的输入框 —— 子代理没在跑就显示「发送消息」，
        // 于是主任务明明还在跑，通知却变成了「空闲」，运行时长也就停住了。
        //
        // 现在改为**先看跨会话的状态标签**（会话列表里的「进行中」/
        // 「{n} 个子代理运行中」），再退回看当前视图的输入框。
        return "(function(){"
             + "if(window.__dshStatusWatch)return;window.__dshStatusWatch=1;"
             + "var last='';var idleStreak=0;"
             + "function visible(e){"
             + "  try{"
             + "    if(!e||!e.getBoundingClientRect)return false;"
             + "    var r=e.getBoundingClientRect();"
             + "    if(r.width<=0||r.height<=0)return false;"
             + "    var st=window.getComputedStyle(e);"
             + "    return st.display!=='none'&&st.visibility!=='hidden';"
             + "  }catch(x){return false;}"
             + "}"
             + "function exact(langs){"
             + "  try{"
             + "    var els=document.querySelectorAll('[aria-label],[title],[placeholder],[data-tooltip]');"
             + "    var i,j,k;"
             + "    for(i=0;i<els.length;i++){"
             + "      var e=els[i];"
             + "      var attrs=['aria-label','title','placeholder','data-tooltip'];"
             + "      for(j=0;j<attrs.length;j++){"
             + "        var v=(e.getAttribute(attrs[j])||'').trim();"
             + "        for(k=0;k<langs.length;k++){if(v===langs[k]&&visible(e))return true;}"
             + "      }"
             + "    }"
             + "    var bs=document.querySelectorAll('button,[role=button],a,span,div');"
             + "    for(i=0;i<bs.length;i++){"
             + "      var tx=(bs[i].textContent||'').trim();"
             + "      for(k=0;k<langs.length;k++){if(tx===langs[k]&&visible(bs[i]))return true;}"
             + "    }"
             + "    return false;"
             + "  }catch(e){return false;}"
             + "}"
             // 「{n} 个子代理运行中」带数字，精确匹配用不了，改用正则
             + "function regexHit(re){"
             + "  try{"
             + "    var all=document.querySelectorAll('span,div,p,li');"
             + "    for(var i=0;i<all.length;i++){"
             + "      var e=all[i];"
             + "      if(e.children&&e.children.length>2)continue;"
             + "      var tx=(e.textContent||'').trim();"
             + "      if(tx.length>0&&tx.length<40&&re.test(tx)&&visible(e))return true;"
             + "    }"
             + "    return false;"
             + "  }catch(e){return false;}"
             + "}"
             + "var RUNNING=['进行中','Running'];"
             + "var SUBAGENT=/^[0-9]+\\s*个子代理运行中$|^[0-9]+\\s+subagents? running$/i;"
             + "var APPR=['允许一次','Allow once','等待审批','Waiting for approval'];"
             + "var STOP=['停止生成','Stop generating'];"
             + "var SEND=['发送消息','Send message'];"
             + "function tick(){"
             + "  try{"
             + "    if(!document.body){return;}"
             + "    var appr=exact(APPR);"
             // 跨会话：主任务或子代理在跑，会话列表里就有状态标签
             + "    var busy=exact(RUNNING)||regexHit(SUBAGENT);"
             + "    var stop=exact(STOP);"
             + "    var send=exact(SEND);"
             + "    var s;"
             + "    if(appr)s='a';"
             + "    else if(busy||stop)s='r';"
             // 空闲要**连续两次**才认定：切换视图的瞬间可能读不到任何按钮，
             // 一次就下结论会让通知在"运行中/空闲"之间抖
             + "    else if(send){idleStreak++;s=(idleStreak>=2)?'i':'r';}"
             + "    else{s='u';}"
             + "    if(s!=='i')idleStreak=0;"
             + "    if(s!==last){last=s;console.log('[dsh-status] '+s);}"
             + "    else{console.log('[dsh-status-keep] '+s);}"
             + "  }catch(e){}"
             + "}"
             + "tick();setInterval(tick,2000);"
             + "})();";
    }

    /**
     * 解析注入脚本上报的状态码。
     *
     * @return 状态常量；不是状态上报时返回 -1
     */
    static int parseStatusConsole(String message) {
        if (message == null) return -1;
        int i = message.indexOf("[dsh-status] ");
        int skip = 13;
        if (i < 0) {
            // 心跳上报：状态没变，但通知需要刷新时长与网络状态
            i = message.indexOf("[dsh-status-keep] ");
            skip = 18;
            if (i < 0) return -1;
        }
        String rest = message.substring(i + skip).trim();
        if (rest.length() == 0) return -1;
        switch (rest.charAt(0)) {
            case 'r': return RUNNING;
            case 'a': return AWAITING_APPROVAL;
            case 'i': return IDLE;
            case 'u': return UNKNOWN;
            default:  return -1;
        }
    }
}
