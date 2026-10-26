/* DSH-ANDROID-CONNECTION-v1 */
// Uses the core's Connection service; no page reload, prompt replay or private RPC.
function androidConnection(ctx, handle) {
    if (!handle.isLoopback || typeof window === 'undefined') return;
    ctx.effect(() => {
        let timer, lastAttempt = 0, disposed = false;
        const report = () => {
            const state = handle.state.getSnapshot();
            window.__dshLocalConnectionState = state;
            const mark = {connected:'open', connecting:'connecting', disconnected:'close'}[state];
            if (mark) console.log('[dsh-conn] ' + mark);
        };
        const check = (event) => {
            clearTimeout(timer);
            if (document.visibilityState === 'hidden') return;
            if (event?.type === 'dsh-native-resume') report();
            timer = setTimeout(() => {
                if (disposed || document.visibilityState === 'hidden') return;
                const state = handle.state.getSnapshot(), now = Date.now();
                // An open healthy generation is kept. Only shorten an existing
                // recovery wait after the app becomes visible again.
                if (!state || state === 'connected' || now - lastAttempt < 2000) return;
                lastAttempt = now;
                handle.reconnect();
            }, 150);
        };
        const off = handle.state.subscribe(report);
        for (const name of ['pageshow','online','dsh-native-resume']) window.addEventListener(name, check);
        document.addEventListener('visibilitychange', check);
        report();
        return () => {
            disposed = true; clearTimeout(timer); off();
            for (const name of ['pageshow','online','dsh-native-resume']) window.removeEventListener(name, check);
            document.removeEventListener('visibilitychange', check);
        };
    });
}
/* DSH-ANDROID-CONNECTION-END */
