# ! Bu araç @mebularts tarafından | @KekikAkademi için yazılmıştır.
from cloudscraper import CloudScraper
from parsel import Selector

oturum  = CloudScraper()
mainUrl = "https://dizipal1103.com"
pageUrl = f"{mainUrl}/yeni-eklenen-bolumler?page=1"

istek   = oturum.get(pageUrl)
secici  = Selector(istek.text)

def icerik_ver(secici: Selector):
    for a in secici.css("a[href^='/bolum/']"):
        print(a.css("::attr(title)").get() or a.css("::text").get())
        print(a.css("::attr(href)").get())
        print()

icerik_ver(secici)
