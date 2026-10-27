'use strict';
const fs = require('node:fs');
const path = require('node:path');

// Bounded tar consumer shared by installation and rollback. File bodies stream
// directly into atomic sibling files; only a header/extended record is retained.
class TarExtractor {
  constructor(destination) {
    fs.mkdirSync(destination, { recursive: true });
    this.root = fs.realpathSync(destination);
    this.header = Buffer.alloc(512); this.used = 0; this.remaining = 0; this.padding = 0;
    this.entry = null; this.global = {}; this.local = {}; this.longName = null; this.longLink = null;
    this.pendingLinks = []; this.ended = false; this.serial = 0;
    this.files = 0; this.dirs = 0; this.links = 0; this.bytes = 0; this.peakMetadata = 512;
  }
  string(buffer, at, size) {
    const zero = buffer.indexOf(0, at);
    return buffer.toString('utf8', at, zero < 0 ? at + size : Math.min(zero, at + size));
  }
  number(buffer, at, size) {
    const text = this.string(buffer, at, size).trim();
    if (text && !/^[0-7]+$/.test(text)) throw new Error('Invalid tar numeric field');
    const value = text ? Number.parseInt(text, 8) : 0;
    if (!Number.isSafeInteger(value) || value < 0) throw new Error('Tar number exceeds safe range');
    return value;
  }
  target(name) {
    if (!name || path.isAbsolute(name) || name.includes('\0') || name.replace(/\\/g, '/').split('/').includes('..')) return null;
    const target = path.resolve(this.root, name);
    if (target !== this.root && !target.startsWith(this.root + path.sep)) return null;
    let parent = path.dirname(target);
    while (parent !== this.root && parent.startsWith(this.root + path.sep)) {
      try { if (fs.lstatSync(parent).isSymbolicLink()) return null; }
      catch (error) { if (error.code !== 'ENOENT') throw error; }
      parent = path.dirname(parent);
    }
    return target;
  }
  pax(buffer) {
    const result = {}; let at = 0, records = 0;
    while (at < buffer.length) {
      const space = buffer.indexOf(32, at);
      if (space < 0 || space - at > 12) throw new Error('Invalid PAX length');
      const digits = buffer.toString('ascii', at, space);
      if (!/^[1-9][0-9]*$/.test(digits)) throw new Error('Invalid PAX length');
      const end = at + Number(digits);
      if (!Number.isSafeInteger(end) || end > buffer.length || end <= space + 1 || buffer[end - 1] !== 10) throw new Error('Truncated PAX record');
      const equals = buffer.indexOf(61, space + 1);
      if (equals < 0 || equals >= end - 1 || ++records > 4096) throw new Error('Invalid PAX field');
      const key = buffer.toString('utf8', space + 1, equals), value = buffer.toString('utf8', equals + 1, end - 1);
      if (['path', 'linkpath', 'size'].includes(key)) result[key] = value;
      at = end;
    }
    return result;
  }
  begin() {
    const header = this.header;
    if (header.every(value => value === 0)) { this.ended = true; return; }
    const wanted = this.number(header, 148, 8); let checksum = 0;
    for (let i = 0; i < 512; i++) checksum += i >= 148 && i < 156 ? 32 : header[i];
    if (wanted !== checksum) throw new Error('Tar header checksum mismatch');
    const type = String.fromCharCode(header[156]);
    const metadata = ['x', 'g', 'L', 'K'].includes(type);
    let size = this.number(header, 124, 12);
    const attrs = metadata ? {} : { ...this.global, ...this.local };
    if (attrs.size !== undefined) {
      if (!/^[0-9]+$/.test(attrs.size)) throw new Error('Invalid PAX file size');
      size = Number(attrs.size);
    }
    if (!Number.isSafeInteger(size) || size < 0 || size > 2 * 1024 ** 3 || (metadata && size > 1024 ** 2)) throw new Error('Tar entry exceeds limit');
    this.bytes += size;
    if (this.bytes > 8 * 1024 ** 3) throw new Error('Tar archive exceeds expansion limit');
    const prefix = this.string(header, 345, 155), short = this.string(header, 0, 100);
    const name = attrs.path ?? this.longName ?? (prefix ? prefix + '/' + short : short);
    const link = attrs.linkpath ?? this.longLink ?? this.string(header, 157, 100);
    const entry = this.entry = { type, name, link, mode: this.number(header, 100, 8), metadata, chunks: [], size };
    this.remaining = size; this.padding = (512 - size % 512) % 512;
    if (metadata) { this.peakMetadata = Math.max(this.peakMetadata, size + 512); return; }
    this.local = {}; this.longName = null; this.longLink = null;
    entry.target = this.target(name);
    if (!entry.target) return;
    if (type === '5') {
      try { if (fs.lstatSync(entry.target).isSymbolicLink()) fs.unlinkSync(entry.target); }
      catch (error) { if (error.code !== 'ENOENT') throw error; }
      fs.mkdirSync(entry.target, { recursive: true }); this.dirs++;
    } else if (type === '0' || type === '\0') {
      if (entry.target === this.root) throw new Error('File cannot replace runtime root');
      fs.mkdirSync(path.dirname(entry.target), { recursive: true });
      entry.temp = path.join(path.dirname(entry.target), '.extract-' + process.pid + '-' + ++this.serial + '.tmp');
      entry.fd = fs.openSync(entry.temp, 'wx', entry.mode & 0o777);
    }
  }
  complete() {
    const entry = this.entry;
    if (entry.metadata) {
      const value = Buffer.concat(entry.chunks, entry.size);
      if (entry.type === 'x') this.local = this.pax(value);
      else if (entry.type === 'g') Object.assign(this.global, this.pax(value));
      else if (entry.type === 'L') this.longName = value.toString('utf8').replace(/\0+$/, '');
      else this.longLink = value.toString('utf8').replace(/\0+$/, '');
    } else if (entry.fd !== undefined) {
      fs.fchmodSync(entry.fd, entry.mode & 0o777);
      fs.closeSync(entry.fd); entry.fd = undefined;
      fs.renameSync(entry.temp, entry.target); entry.temp = null; this.files++;
    } else if (entry.target && entry.type === '2') {
      if (this.pendingLinks.length >= 100000) throw new Error('Too many runtime links');
      this.pendingLinks.push([entry.name, entry.link]);
    }
    this.entry = null;
  }
  write(chunk) {
    let at = 0;
    while (at < chunk.length) {
      if (this.ended) {
        for (; at < chunk.length; at++) if (chunk[at] !== 0) throw new Error('Unexpected data after tar end');
      } else if (this.remaining > 0) {
        const count = Math.min(this.remaining, chunk.length - at), data = chunk.subarray(at, at + count);
        if (this.entry.metadata) this.entry.chunks.push(Buffer.from(data));
        else if (this.entry.fd !== undefined) {
          let written = 0;
          while (written < data.length) {
            const count = fs.writeSync(this.entry.fd, data, written, data.length - written);
            if (count <= 0) throw new Error('Runtime file write incomplete');
            written += count;
          }
        }
        at += count; this.remaining -= count;
        if (this.remaining === 0) this.complete();
      } else if (this.padding) {
        const count = Math.min(this.padding, chunk.length - at); this.padding -= count; at += count;
      } else {
        const count = Math.min(512 - this.used, chunk.length - at);
        chunk.copy(this.header, this.used, at, at + count); this.used += count; at += count;
        if (this.used === 512) {
          this.used = 0; this.begin();
          if (this.entry && this.remaining === 0) this.complete();
        }
      }
    }
  }
  finish() {
    if (!this.ended || this.entry || this.remaining || this.padding || this.used) throw new Error('Truncated tar archive');
    for (const [name, link] of this.pendingLinks) {
      const target = this.target(name);
      if (!target || target === this.root || !link || link.includes('\0')) continue;
      fs.mkdirSync(path.dirname(target), { recursive: true });
      try { fs.unlinkSync(target); } catch (error) { if (error.code !== 'ENOENT') throw error; }
      try { fs.symlinkSync(link, target); }
      catch (error) {
        const resolved = this.target(path.relative(this.root, path.resolve(path.dirname(target), link)));
        if (!resolved || !fs.statSync(resolved).isFile()) throw error;
        fs.copyFileSync(resolved, target, fs.constants.COPYFILE_EXCL);
      }
      this.links++;
    }
    return { files: this.files, dirs: this.dirs, links: this.links, peakMetadata: this.peakMetadata };
  }
  abort() {
    if (this.entry?.fd !== undefined) { fs.closeSync(this.entry.fd); this.entry.fd = undefined; }
    if (this.entry?.temp) fs.rmSync(this.entry.temp, { force: true });
  }
}
module.exports = { TarExtractor };
