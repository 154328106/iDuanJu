#!/usr/bin/env python3
"""Convert a reviewed, legal M3U channel list to a static, permission-scoped idj1 bundle.
Does not fetch URLs, execute scripts, copy cookies, or claim arbitrary TVBox support.
"""
import argparse, base64, hashlib, ipaddress, json, re
from urllib.parse import urljoin, urlsplit, parse_qsl
from pathlib import Path


def public_url(raw, base=None):
    url = urljoin(base or '', raw.strip())
    if re.search(r'[\x00-\x20\x7f\\]', url):
        raise ValueError('URL contains whitespace, controls or backslashes')
    u = urlsplit(url)
    if u.scheme not in ('https', 'http') or not u.hostname or u.username or u.password:
        raise ValueError('only credential-free HTTP(S) URLs are supported')
    if u.port and u.port != (443 if u.scheme == 'https' else 80):
        raise ValueError('nonstandard ports need a reviewed adapter')
    host = u.hostname.lower()
    if not host.isascii() or host.endswith('.') or all(re.fullmatch(r'(?:0x[0-9a-f]+|[0-9]+)', part, re.I) for part in host.split('.')):
        raise ValueError('ambiguous numeric, trailing-dot or non-ASCII host needs a reviewed adapter')
    if not re.fullmatch(r'(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?', host):
        raise ValueError('invalid DNS hostname')
    try:
        ipaddress.ip_address(host)
    except ValueError:
        if '.' not in host or host.endswith(('.local', '.localhost', '.internal')) or host == 'localhost':
            raise ValueError('local hosts are not supported')
    else:
        raise ValueError('IP literal URLs are not supported by idj1 permissions')
    # Do not turn expiring/secret URL credentials into persisted episode IDs.
    sensitive = re.compile(r'(?i)^(?:token|access[_-]?token|auth|auth_key|authorization|key|api[_-]?key|password|pass|secret|sign|signature|sig|cookie|x-amz-.+|x-goog-.+)$')
    if any(sensitive.fullmatch(key) for key, _ in parse_qsl(u.query, keep_blank_values=True) + parse_qsl(u.fragment, keep_blank_values=True)):
        raise ValueError('signed/authenticated URL needs an adapter; do not persist credentials')
    return url, host


def parse(text, base=None):
    lines = text.lstrip('\ufeff').splitlines()
    if not lines or lines[0].strip() != '#EXTM3U':
        raise ValueError('expected an extended M3U channel list')
    if any(re.match(r'#EXT-X-', line.strip(), re.I) for line in lines):
        raise ValueError('this is an HLS media/master playlist, not a channel list')
    items, meta, headers = [], None, {}
    for line in lines[1:]:
        line = line.strip()
        if not line:
            continue
        if line.startswith('#EXTINF:'):
            if meta is not None:
                raise ValueError('channel is missing its URL')
            match = re.match(r'#EXTINF:(?:[^",]|"[^"]*")*,(.*)$', line)
            if not match or not match.group(1).strip():
                raise ValueError('EXTINF is missing a channel name')
            attrs = dict(re.findall(r'([\w-]+)="([^"]*)"', line[:match.start(1)]))
            meta = dict(title=match.group(1).strip(), group=attrs.get('group-title') or '全部')
            if attrs.get('tvg-logo'):
                meta['cover'] = public_url(attrs['tvg-logo'], base)[0]
            headers = {}
        elif line.startswith('#EXTGRP:') and meta is not None:
            meta['group'] = line.partition(':')[2].strip() or '全部'
        elif line.startswith('#EXTVLCOPT:'):
            key, _, value = line[len('#EXTVLCOPT:'):].partition('=')
            mapped = {'http-referrer': 'Referer', 'http-user-agent': 'User-Agent'}.get(key.lower())
            if meta is None or not mapped or len(value)>500 or any(ord(c)<32 or ord(c)==127 for c in value):
                raise ValueError('unsupported/unsafe VLC option: ' + key)
            if mapped == 'Referer': public_url(value)
            headers[mapped] = value
        elif line.startswith(('#KODIPROP:', '#EXTHTTP:')):
            raise ValueError('DRM/auth/extra HTTP directives require a reviewed adapter')
        elif line.startswith('#'):
            continue
        else:
            if meta is None:
                raise ValueError('channel URL must follow EXTINF')
            if '|' in line:
                raise ValueError('inline HTTP headers are not supported; use explicit reviewed VLC options')
            url, _ = public_url(line, base)
            if urlsplit(url).scheme != 'https':
                raise ValueError('native video resolver requires HTTPS; HTTP-only streams are unsupported')
            item = dict(meta, id=str(len(items) + 1), url=url, headers=headers)
            items.append(item); meta = None
    if meta is not None or not items:
        raise ValueError('empty or truncated channel list')
    return items


def bundle(items, name, plugin_id):
    if not re.fullmatch(r'[a-z0-9]+(?:\.[a-z0-9-]+){2,}', plugin_id):
        raise ValueError('plugin id must be a reverse domain')
    domains = sorted({public_url(i[k])[1] for i in items for k in ('url', 'cover') if i.get(k)})
    source = 'const channels = ' + json.dumps(items, ensure_ascii=False) + ';\n' + r"""
function find(id) { const c=channels.find(x => x.id===String(id)); if (!c) throw host.error('E_NOT_FOUND','频道不存在'); return c; }
function page(items, n) { const size=50, p=Math.max(1,Number(n)||1); return {items:items.slice((p-1)*size,p*size),page:p,hasMore:p*size<items.length}; }
export default {
 home() { return {categories:Array.from(new Set(channels.map(x=>x.group))).map(x=>({id:x,name:x})),items:channels.slice(0,50)}; },
 category({id,page:p}) { return page(channels.filter(x=>!id||x.group===id),p); },
 search({keyword,page:p}) { return page(channels.filter(x=>x.title.toLowerCase().includes(String(keyword||'').toLowerCase())),p); },
 detail({id}) { const c=find(id); return {title:c.title,cover:c.cover,desc:'M3U 静态导入；原始内容归其权利人所有。',lines:[{name:c.group,episodes:[{id:c.id,name:c.title}]}]}; },
 play({episodeId}) { const c=find(episodeId); return {streams:[{url:c.url,headers:c.headers,format:/\.m3u8(?:[?#]|$)/i.test(c.url)?'hls':'mp4'}]}; }
};
"""
    manifest = dict(schema=1,id=plugin_id,name=name,version='1.0.0',author={'name':'iDuanJu compatibility test'},type='video',adult=False,minAppVersion='1.5',entry='main.js',permissions={'http':domains,'media':domains},capabilities=['home','category','search','detail','play'],integrity={'files':{'main.js':'sha256-'+base64.b64encode(hashlib.sha256(source.encode()).digest()).decode()}})
    return {'manifest':manifest,'files':{'main.js':source}}


if __name__ == '__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('input');p.add_argument('output');p.add_argument('--base');p.add_argument('--name',default='M3U 测试列表');p.add_argument('--id',default='com.idj.test.m3u')
    a=p.parse_args()
    try:
        data=bundle(parse(Path(a.input).read_text(encoding='utf-8-sig'),a.base),a.name,a.id)
        Path(a.output).write_text(json.dumps(data,ensure_ascii=False,indent=2),encoding='utf-8')
    except ValueError as e: p.error(str(e))
