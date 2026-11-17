import unittest
from m3u_to_idj import parse,bundle

class M3UTest(unittest.TestCase):
 def test_bom_crlf_metadata_comma_relative_headers(self):
  a=parse('\ufeff#EXTM3U\r\n#EXTINF:-1 tvg-id="a" group-title="News, HD" tvg-logo="/a.png",Title, HD\r\n#EXTVLCOPT:http-referrer=https://example.org/\r\n#EXTVLCOPT:http-user-agent=Player\r\n./live.m3u8\r\n','https://media.example.org/lists/a.m3u')
  self.assertEqual(a[0]['group'],'News, HD');self.assertEqual(a[0]['title'],'Title, HD')
  self.assertEqual(a[0]['url'],'https://media.example.org/lists/live.m3u8')
  self.assertEqual(a[0]['headers']['Referer'],'https://example.org/')
  self.assertEqual(bundle(a,'Test','org.example.test')['manifest']['permissions']['http'],['media.example.org'])
 def test_reject_hls_drm_credentials_local_and_broken(self):
  for value in ['#EXTM3U\n#EXT-X-TARGETDURATION:10\n#EXTINF:10,\na.ts',
     '#EXTM3U\n#EXTINF:-1,A\n#KODIPROP:inputstream.adaptive.license_key=x\nhttps://a.org/a',
     '#EXTM3U\n#EXTINF:-1,A', '#EXTM3U\nhttps://a.org/a',
     *['#EXTM3U\n#EXTINF:-1,A\n'+u for u in ['file:///etc/passwd','http://127.0.0.1/a','http://localhost/a','https://user:pass@a.org/a','https://a.org/a?token=secret','udp://a.org/a','https://a.org/a|Cookie=x']]]:
   with self.subTest(value=value):
    with self.assertRaises(ValueError):parse(value)
 def test_duplicate_titles_keep_unique_ids(self):
  a=parse('#EXTM3U\n#EXTINF:-1,A\nhttps://a.org/a\n#EXTINF:-1,A\nhttps://a.org/b')
  self.assertEqual([x['id'] for x in a],['1','2'])
if __name__=='__main__': unittest.main()
