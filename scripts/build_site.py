#!/usr/bin/env python3
"""Generate the five static GitHub Pages editions; no runtime dependencies."""
from pathlib import Path
import json
import re
from html import escape as e
ROOT = Path(__file__).resolve().parents[1]
SITE = ROOT / 'site'
data = json.loads((SITE / 'content.json').read_text())
BASE = 'https://zutaozhong-hash.github.io/DiAuto-Pro/'
REPO = 'https://github.com/zutaozhong-hash/DiAuto-Pro'
VERSION = '0.3.12'
TAG = 'v' + VERSION
TELEGRAM = 'https://t.me/DiAutoPro'
RELEASE = REPO + '/releases/tag/' + TAG
DOWNLOAD = REPO + '/releases/download/' + TAG + '/DiAuto-Pro-' + VERSION + '.apk'
BOLD = re.compile(r'\*\*(.+?)\*\*', re.S)
CODE = re.compile(r'`([^`]+)`')


def rich(text):
    """Escape, then turn **bold** and `code` into markup. Content files stay plain text."""
    out = e(text)
    out = BOLD.sub(r'<strong>\1</strong>', out)
    out = CODE.sub(r'<code>\1</code>', out)
    return out


def items(values, tag):
    return ''.join('<li>' + rich(x) + '</li>' for x in values)


def highlight_block(d):
    cards = ''.join(
        '<section class="card hl"><span class="eyebrow hl-eyebrow">' + rich(c['eyebrow']) + '</span>'
        '<h3 class="hl-title">' + rich(c['title']) + '</h3>'
        '<p>' + rich(c['body']) + '</p>'
        '<ul>' + items(c['points'], 'li') + '</ul></section>'
        for c in d['cards'])
    head = ('<tr>' + ''.join('<th scope="col">' + rich(c) + '</th>' for c in d['specColumns']) + '</tr>')
    body = ''.join('<tr>' + ''.join('<td>' + rich(c) + '</td>' for c in row) + '</tr>'
                   for row in d['specRows'])
    return (f'<section class="highlights"><h2>{rich(d["highlightsTitle"])}</h2>'
            f'<p class="lead">{rich(d["highlightsLead"])}</p>'
            f'<div class="grid hl-grid">{cards}</div>'
            f'<h3 class="spec-head">{rich(d["specTitle"])}</h3>'
            f'<div class="table-wrap"><table class="spec"><thead>{head}</thead><tbody>{body}</tbody></table></div>'
            f'<p class="note">{rich(d["specNote"])}</p></section>')
for lang, d in data.items():
    folder = SITE if lang == 'en' else SITE / lang
    folder.mkdir(exist_ok=True)
    prefix = './' if lang == 'en' else '../'
    url = BASE + ('' if lang == 'en' else lang + '/')
    nav = ''.join(f'<a href="{prefix}{"" if code == "en" else code + "/"}" lang="{code}" hreflang="{code}" dir="auto"'+(' aria-current="page"' if code == lang else '')+f'>{e(v["name"])}</a>' for code,v in data.items())
    alternates = ''.join(f'<link rel="alternate" hreflang="{code}" href="{BASE}{"" if code == "en" else code + "/"}">' for code in data)
    pics = ''.join(f'<figure><a href="{prefix}assets/{pic}.png"><img src="{prefix}assets/{pic}.png" width="1920" height="1080" loading="lazy" alt="{e(cap)}"></a><figcaption>{e(cap)}</figcaption></figure>' for pic,cap in zip(['projection-music','home','settings'],d['captions']))
    (folder/'index.html').write_text(f'''<!doctype html>
<html lang="{lang}" dir="{d['dir']}"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>DiAuto Pro · {e(d['download'])}</title><meta name="description" content="{e(d['intro'])}"><meta name="theme-color" content="#0c121c">
<link rel="icon" href="{prefix}assets/icon.svg"><link rel="stylesheet" href="{prefix}assets/site.css"><link rel="canonical" href="{url}">{alternates}
<meta property="og:title" content="DiAuto Pro — Android Auto for BYD"><meta property="og:description" content="{e(d['promise'])}"><meta property="og:image" content="{BASE}assets/projection-music.png"><meta property="og:url" content="{url}"><meta property="og:type" content="website">
</head><body><main>
<header><a class="brand" href="{prefix}"><img src="{prefix}assets/icon.svg" width="56" height="56" alt=""><span><strong>DiAuto Pro</strong><small>{e(d['tag'])}</small></span></a><nav class="languages" aria-label="Language">{nav}</nav></header>
<section class="hero"><span class="badge">{e(d['badge'])} · <bdi>{VERSION}</bdi></span><h1>{e(d['title']).replace(chr(10),'<br>')}</h1><p class="intro">{e(d['intro'])}</p><div class="actions"><a class="button" href="{DOWNLOAD}">{e(d['download'])} <span aria-hidden="true">↓</span></a><a class="button secondary" href="#install">{e(d['install'])}</a></div><p class="promise">{e(d['promise'])}</p><p class="note">{e(d['requires'])}</p><p class="note support-scope"><strong>{e(d['supportScope'])}</strong></p></section>
{highlight_block(d)}
<section class="gallery"><h2>{e(d['gallery'])}</h2><div class="screens">{pics}</div></section>
<div class="grid"><section class="card" id="install"><span class="eyebrow">01</span><h2>{e(d['setup'])}</h2><ol>{items(d['steps'],'li')}</ol><p class="note">{e(d['bssid'])}</p><a href="{REPO}/blob/main/docs/INSTALL.md">{e(d['adb'])} ↗</a></section>
<section class="card"><span class="eyebrow">02</span><h2>{e(d['whats'])}</h2><ul>{items(d['features'],'li')}</ul><a href="{RELEASE}">{e(d['notes'])} ↗</a><h3>{e(d['compat'])}</h3><p>{rich(d['compatText'])}</p></section></div>
<section class="card updates"><div><h2>{e(d['follow'])}</h2><p>{e(d['followText'])}</p></div><a class="button secondary" href="{TELEGRAM}">{e(d['telegram'])} ↗</a></section>
<section class="signing"><h2>{e(d['update'])}</h2><p>{e(d['updateText'])}</p></section>
<footer><nav><a href="{REPO}">{e(d['source'])}</a><a href="{RELEASE}">{e(d['notes'])}</a><a href="{REPO}/issues">{e(d['feedback'])}</a></nav><p>{e(d['footer'])}</p></footer>
</main></body></html>''')
print('Generated', len(data), 'language pages')
