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
        // 只负责一件事：**检测待批准**。
        //
        // 运行/空闲状态不在这里判断 —— 它由 App 从 DSH 自己的
        // /api/session/list 响应里读（那个接口是 RPC 式 POST，
        // 注入脚本用 GET 调只会 404；实测 109 次 404 全是这么来的）。
        //
        // 审批是会话的「待处理交互」，会话列表里没有这个字段，
        // 所以仍需从界面读 —— 但用**精确匹配 + 可见性**双重限制：
        //   * 精确匹配：避免对话正文里出现这几个字就误判
        //   * 可见性：避免已处理的旧面板仍留在 DOM 里造成误判
        return "(function(){"
             + "if(window.__dshStatusWatch)return;window.__dshStatusWatch=1;"
             + "var last='';"
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
             + "    var bs=document.querySelectorAll('button,[role=button],a');"
             + "    for(i=0;i<bs.length;i++){"
             + "      var tx=(bs[i].textContent||'').trim();"
             + "      for(k=0;k<langs.length;k++){if(tx===langs[k]&&visible(bs[i]))return true;}"
             + "    }"
             + "    return false;"
             + "  }catch(e){return false;}"
             + "}"
             + "var APPR=['允许一次','Allow once','等待审批','Waiting for approval'];"
             + "function tick(){"
             + "  try{"
             + "    if(!document.body)return;"
             + "    var a=exact(APPR)?'a':'-';"
             + "    if(a!==last){last=a;console.log('[dsh-appr] '+a);}"
             + "  }catch(e){}"
             + "}"
             + "tick();setInterval(tick,2000);"
             + "})();";
    }

    /**
     * 触摸屏适配：让「悬停菜单」在手指抬起后不要立刻关闭。
     *
     * <h3>问题</h3>
     * DSH 的会话操作菜单（重命名 / 分叉 / 归档）用的是悬停卡片，
     * 组件上写着 {@code closeOnPointerLeave: true}。鼠标上是合理的：
     * 移开就收起。但**手指抬起同样会产生 pointerleave** ——
     * 于是菜单在点中的瞬间就被关掉，用户点的其实是一片空白。
     *
     * <p>实测证据：WebSocket 探针显示所有 RPC 里**没有任何一条 request**
     *（归档、重命名都是 request），只有订阅用的 open ——
     * 说明点击根本没走到处理函数，问题出在菜单本身。
     *
     * <h3>做法</h3>
     * 记录最近一次触摸时间；在**捕获阶段**拦掉紧随触摸之后的
     * {@code pointerleave}。React 的事件是委托到根节点的，
     * 捕获阶段停掉传播，它就不会收到这个事件，菜单也就不会关。
     *
     * <p>只影响触摸后的短窗口（800ms），鼠标行为不受影响。
     *
     * <p><b>已验证有效</b>：装上后归档与重命名都能正常工作。
     * 若将来 DSH 换了菜单实现，这里的拦截可能失效 —— 判断方法见
     * docs/GOTCHAS.md 里「点了没反应」的排查步骤。
     */
    static String touchMenuFixScript() {
        return "(function(){"
             + "if(window.__dshTouchFix)return;window.__dshTouchFix=1;"
             + "var lastTouch=0;"
             + "function mark(){lastTouch=Date.now();}"
             + "document.addEventListener('touchstart',mark,true);"
             + "document.addEventListener('touchend',mark,true);"
             + "document.addEventListener('pointerdown',function(e){"
             + "  if(e.pointerType==='touch')mark();"
             + "},true);"
             + "function block(e){"
             + "  if(Date.now()-lastTouch<800){"
             + "    e.stopPropagation();"
             + "    if(e.stopImmediatePropagation)e.stopImmediatePropagation();"
             + "  }"
             + "}"
             + "document.addEventListener('pointerleave',block,true);"
             + "document.addEventListener('mouseleave',block,true);"
             + "document.addEventListener('pointerout',block,true);"
             + "console.log('[dsh-touch] 触摸菜单修复已安装');"
             + "})();";
    }

    /**
     * 解析注入脚本上报的状态码。
     *
     * @return 状态常量；不是状态上报时返回 -1
     */
    static int parseStatusConsole(String message) {
        if (message == null) return -1;
        // 只上报「待批准」——运行/空闲由 App 从会话列表响应里读
        int i = message.indexOf("[dsh-appr] ");
        if (i < 0) return -1;
        String rest = message.substring(i + 11).trim();  // "[dsh-appr] " 共 11 字符
        if (rest.length() == 0) return -1;
        return rest.charAt(0) == 'a' ? AWAITING_APPROVAL : -1;
    }

    /** 页面侧上报的会话列表计数前缀。 */
    static final String SESS_MARK = "[dsh-sess] ";
    /** 页面侧上报的 DOM 按钮状态前缀。 */
    static final String DOM_MARK = "[dsh-dom] ";

    /**
     * 解析页面侧上报的会话列表计数：{@code [dsh-sess] r=<运行中会话数>}。
     *
     * <h3>为什么要有这个信号</h3>
     * 原来是 App 直接在控制台文本里数 {@code "running":true}，而那段文本被
     * {@code slice(0,700)} 截断过 —— 运行中的会话只要不是列表第一项，
     * 它的 {@code "running":true} 就落在 700 字符之外被截掉，计数为 0，
     * 于是**正在跑的任务被判成「空闲」**。
     *
     * <p>现在改由页面在**截断之前**解析完整 JSON 并上报计数。因此
     * {@code r=0} 是「确实没有会话在跑」的**可信证据**，而不是「没读到」。
     *
     * @return RUNNING / IDLE；不是该信号时返回 -1
     */
    static int parseSessionConsole(String message) {
        if (message == null) return -1;
        int i = message.indexOf(SESS_MARK);
        if (i < 0) return -1;
        String rest = message.substring(i + SESS_MARK.length()).trim();
        int eq = rest.indexOf("r=");
        if (eq < 0) return -1;
        int n = -1;
        for (int k = eq + 2; k < rest.length(); k++) {
            char ch = rest.charAt(k);
            if (ch < '0' || ch > '9') break;
            int d = ch - '0';
            n = Math.min(999, (n < 0 ? 0 : n) * 10 + d);   // 饱和：脏输入不该影响判定
        }
        if (n < 0) return -1;
        return n > 0 ? RUNNING : IDLE;
    }

    /**
     * 解析页面侧上报的 DOM 按钮状态：
     * {@code [dsh-dom] s=<停止生成> n=<发送消息> p=<等待审批>}。
     *
     * <p>这是**第二个独立证据源**。会话列表信号只在 DSH 自己请求时才有，
     * 而 DOM 每 2 秒就看一次 —— 它让状态在会话列表长时间不刷新时
     * 仍能从「运行中」正确恢复，也让「等待批准」有独立的确认来源。
     *
     * @return 对应状态；三个标志全为 0（页面正在切换）时返回 UNKNOWN；不是该信号时返回 -1
     */
    static int parseDomConsole(String message) {
        if (message == null) return -1;
        int i = message.indexOf(DOM_MARK);
        if (i < 0) return -1;
        String rest = message.substring(i + DOM_MARK.length()).trim();
        boolean s = rest.indexOf("s=1") >= 0;
        boolean n = rest.indexOf("n=1") >= 0;
        boolean p = rest.indexOf("p=1") >= 0;
        if (!s && !n && !p) return UNKNOWN;
        return fromDom(s, n, p, true);
    }
}
