# Reviewed source compatibility examples

- `classic-source.json`: unchanged 古典文学 entry from yck collection 1340 (index 352), https://www.zggdwx.com/; public-domain classic text. Import through **小说 → 导入书源**. Native catalog/detail/TOC/reader tested on iOS and Android. This source has no search rule; do not infer search support.
- `legal-animation.m3u`: static extended-M3U pointing to the official Mux/hls.js Big Buck Bunny test stream, https://test-streams.mux.dev/. No commercial anime catalogs or scraping rules are bundled.
- `legal-animation.idjplugin`: reviewed conversion of that playlist; import as a **plugin bundle**, not a novel source or subscription. Native detail/play tested on both platforms. Its content is intentionally type `video` because the existing home UI does not list `live` plugins.

Regenerate the static plugin (Python standard library only):

```sh
python3 scripts/source-compat/m3u_to_idj.py examples/source-compat/legal-animation.m3u examples/source-compat/legal-animation.idjplugin --id com.idj.test.animation --name '公开测试动画 M3U'
python3 -m unittest discover -s scripts/source-compat -p 'test_*.py'
```

This is not a TVBox/JAR engine, HLS-playlist importer, dynamic M3U subscription, or Legado audio/video/RSS runtime. The converter rejects executable spiders, DRM settings, unsafe URLs/headers and HLS media/master playlists. Only trusted, legally usable entries should be converted and reviewed before installation.

Source provenance: https://www.yckceo.com/yuedu/shuyuans/content/id/1340.html . Repository membership does not establish a source's copyright/license or availability. Generate the two-source private ranking regression subset with `python3 scripts/source-compat/prepare_rank_fixture.py /path/to/1335.json`; its output is gitignored at `android/app/src/androidTest/assets/source-compat/filter-sources.json`; no paid chapters are accessed. Do not redistribute this private subset without source license review.
