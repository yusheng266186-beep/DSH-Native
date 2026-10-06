/* DSH-ANDROID-SESSION-TOOLS-v1 */
import { readFile as androidReadFile, writeFile as androidWriteFile, rename as androidRename,
    open as androidOpen, unlink as androidUnlink } from 'node:fs/promises';
import { homedir as androidHome } from 'node:os';
import { WorkspaceActiveSessionError as AndroidActiveSession } from '@deepseek-ai/dsh-workspace';

// Deletion is a durable, recoverable trash operation. Session files stay owned
// by the core; the App never edits a live log or removes a workspace directory.
// Remote calls may bind a different service proxy per request. Serialize by data home, not proxy identity.
const androidTrashStates = new Map();
function androidTrashState(controller) {
    const directory = resolve(process.env.DSH_HOME || resolve(androidHome(), '.dsh'));
    let state = androidTrashStates.get(directory);
    if (!state) {
        state = { directory, path: resolve(directory, '.native-session-trash.json'), tail: Promise.resolve(), load: null };
        androidTrashStates.set(directory, state);
    }
    return state;
}
async function androidReadTrash(controller) {
    const state = androidTrashState(controller);
    if (!state.load) state.load = (async () => {
        let bytes;
        try { bytes = await androidReadFile(state.path); }
        catch (error) { if (error?.code === 'ENOENT') return new Map(); throw error; }
        if (bytes.length > 1024 * 1024) throw new Error('session trash exceeds its limit');
        const data = JSON.parse(bytes.toString('utf8'));
        if (data.version !== 1 || !Array.isArray(data.items) || data.items.length > 10000)
            throw new Error('invalid session trash');
        const items = new Map();
        for (const item of data.items) {
            if (typeof item.id !== 'string' || !item.id || item.id.length > 512 || items.has(item.id)
                || !Number.isSafeInteger(item.deletedAt) || item.deletedAt <= 0) throw new Error('invalid session trash entry');
            items.set(item.id, item.deletedAt);
        }
        return items;
    })().catch(error => { state.load = null; throw error; });
    return state.load;
}
async function androidWriteTrash(controller, items) {
    const state = androidTrashState(controller);
    const bytes = JSON.stringify({ version: 1, items: [...items].map(([id, deletedAt]) => ({id, deletedAt})) });
    if (items.size > 10000 || Buffer.byteLength(bytes) > 1024 * 1024) throw new Error('session trash is full');
    await mkdir(state.directory, {recursive: true});
    const staged = state.path + '.' + randomUUID() + '.tmp';
    try {
        await androidWriteFile(staged, bytes, {flag: 'wx', mode: 0o600});
        const file = await androidOpen(staged, 'r+');
        try { await file.sync(); } finally { await file.close(); }
        await androidRename(staged, state.path);
        state.load = Promise.resolve(items);
    } finally {
        await androidUnlink(staged).catch(error => { if (error?.code !== 'ENOENT') throw error; });
    }
}
function androidSerializeTrash(controller, work) {
    const state = androidTrashState(controller);
    const task = state.tail.then(work);
    state.tail = task.catch(() => {}); // The caller receives the failure; later operations can retry.
    return task;
}
function androidSessionId(request) {
    if (typeof request?.sessionId !== 'string' || !request.sessionId || request.sessionId.length > 512)
        throw new RemoteError('gateway/bad-request', 'A session identity is required.', {});
    return request.sessionId;
}
function androidInstallSessionTools(Controller) {
    const prototype = Controller.prototype;
    const originalList = prototype.list;
    const originalSearch = prototype.search;
    const rawList = (controller, signal) => originalList.call(controller, {}, signal);
    prototype.list = async function(request, signal) {
        const [result, trash] = await Promise.all([originalList.call(this, request, signal), androidReadTrash(this)]);
        return {...result, items: result.items.filter(row => !trash.has(row.sessionId))};
    };
    prototype.search = async function(request, signal) {
        const [result, trash] = await Promise.all([originalSearch.call(this, request, signal), androidReadTrash(this)]);
        return {...result, items: result.items.filter(row => !trash.has(row.sessionId))};
    };
    for (const name of ['create', 'prompt', 'fork', 'rename', 'selectModel', 'updateQueue']) {
        const original = prototype[name];
        prototype[name] = function(request, ...args) {
            return androidSerializeTrash(this, async () => {
            if (request?.sessionId && (await androidReadTrash(this)).has(request.sessionId))
                throw new RemoteError('android/session-trashed', 'Restore this session from the trash before continuing.', {});
            return original.call(this, request, ...args);
            });
        };
    }
    const methods = {
        androidList: async function(_request, signal) {
            const [result, trash] = await Promise.all([rawList(this, signal), androidReadTrash(this)]);
            const archived = new Set(this.ctx.workspaceRegistry.archivedSessionIds);
            return {items: result.items.filter(row => row.origin !== 'subagent').map(row => ({sessionId:row.sessionId, updatedAt:row.updatedAt, running:row.running, title: typeof row.projections?.values?.title === 'string' ? row.projections.values.title : null, archived: archived.has(row.sessionId),
                trashed: trash.has(row.sessionId), deletedAt: trash.get(row.sessionId) ?? null}))};
        },
        androidTrash: function(request) {
            const id = androidSessionId(request);
            return androidSerializeTrash(this, async () => {
                const trash = await androidReadTrash(this);
                if (trash.has(id)) return {sessionId: id, trashed: true};
                if (!(await rawList(this)).items.some(row => row.sessionId === id))
                    throw new RemoteError('session/not-found', 'This session no longer exists.', {});
                const wasArchived = this.ctx.workspaceRegistry.archivedSessionIds.includes(id);
                // The registry rejects running agents, queued work and active children.
                try { await this.ctx.workspaceRegistry.archiveSession(id); }
                catch (error) {
                    if (error instanceof AndroidActiveSession) throw new RemoteError('workspace/session-active', 'This session still has active work.', {sessionId:id, activity:error.activity});
                    throw error;
                }
                const next = new Map(trash); next.set(id, Date.now());
                try { await androidWriteTrash(this, next); }
                catch (error) {
                    if (!wasArchived) await this.ctx.workspaceRegistry.unarchiveSession(id);
                    throw error;
                }
                this.ctx.emit('api-session/removed', id);
                return {sessionId: id, trashed: true};
            });
        },
        androidRestore: function(request) {
            const id = androidSessionId(request);
            return androidSerializeTrash(this, async () => {
                const trash = await androidReadTrash(this);
                if (!(await rawList(this)).items.some(row => row.sessionId === id))
                    throw new RemoteError('session/not-found', 'This session no longer exists.', {});
                await this.ctx.workspaceRegistry.unarchiveSession(id);
                if (trash.has(id)) {
                    const next = new Map(trash); next.delete(id);
                    try { await androidWriteTrash(this, next); }
                    catch (error) { await this.ctx.workspaceRegistry.archiveSession(id); throw error; }
                }
                const row = (await rawList(this)).items.find(row => row.sessionId === id);
                if (row) this.ctx.emit('api-session/added', row);
                return {sessionId: id, trashed: false};
            });
        }
    };
    for (const [name, method] of Object.entries(methods)) {
        Object.defineProperty(prototype, name, {value: method, writable: true, configurable: true});
        Remote(name)(method, {kind: 'method', name, static: false, private: false,
            addInitializer: initialize => initialize.call(Object.create(prototype))});
    }
}
/* DSH-ANDROID-SESSION-TOOLS-END */
