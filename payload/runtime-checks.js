// Probe the actual file-tool backend, not the availability of the ln command.
const fs = require('node:fs/promises');
const path = require('node:path');
const {pathToFileURL} = require('node:url');

async function checkFileTool(runtime, directory) {
    let scratch, stage = 'import';
    try {
        const {Context} = await import(pathToFileURL(path.join(runtime, 'node_modules/@deepseek-ai/cordis/lib/index.js')));
        const {LocalFileSystem} = await import(pathToFileURL(path.join(runtime, 'node_modules/@deepseek-ai/dsh-fs-local/lib/index.js')));
        stage = 'temporary-directory';
        scratch = await fs.mkdtemp(path.join(directory, '.dsh-write-check-'));
        const backend = new LocalFileSystem(new Context(), {cwd:scratch, diffBasisMaxBytes:65536});
        const target = await backend.resolve('probe.txt');
        stage = 'create';
        const created = await backend.writeText(target, 'DSH file tool check', {kind:'createIfAbsent'});
        if (created.operation !== 'create') throw new Error('unexpected create outcome');
        stage = 'read';
        if (await backend.readText(target) !== 'DSH file tool check') throw new Error('read mismatch');
        stage = 'collision-guard';
        let protectedTarget = false;
        try { await backend.writeText(target, 'must not replace', {kind:'createIfAbsent'}); }
        catch (error) { if (error.code !== 'FS_NOT_OBSERVED') throw error; protectedTarget = true; }
        if (!protectedTarget || await backend.readText(target) !== 'DSH file tool check')
            throw new Error('existing file was not protected');
        stage = 'replace';
        await backend.writeText(target, 'DSH updated check', {kind:'replaceIfVersion', version:created.version});
        if (await backend.readText(target) !== 'DSH updated check') throw new Error('replace mismatch');
        return '新建、读取、同名保护与更新正常';
    } catch (error) {
        // Diagnostics contain stages/codes, never file contents or user paths.
        const code = String(error.code || error.name || 'Error').replace(/[^A-Za-z0-9_-]/g, '').slice(0,64);
        throw new Error('file-tool ' + stage + ' (' + code + ')');
    } finally {
        if (scratch) {
            try { await fs.rm(scratch, {recursive:true, force:true}); }
            catch (error) { throw new Error('file-tool cleanup (' + (error.code || 'Error') + ')'); }
        }
    }
}

module.exports = {checkFileTool};
if (require.main === module) {
    checkFileTool(path.resolve(process.argv[2] || path.join(__dirname, 'dsh')),
        path.resolve(process.argv[3] || process.cwd()))
        .then(detail => console.log('FILE_TOOL|PASS|' + detail))
        .catch(error => {console.error('FILE_TOOL|FAIL|' + error.message); process.exitCode = 1;});
}
