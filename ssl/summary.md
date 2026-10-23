## SSL / Cloudflare পরীক্ষা

### সার্টিফিকেট (VPS-এ, Let's Encrypt)
| নাম | মেয়াদ শেষ | বাকি দিন | কে দিয়েছে |
|---|---|---|---|
| <vps-ip>.sslip.io  | 2027-01-17 | 89 | Let's Encrypt YE2 |
| api.lifolo.com  | 2027-01-17 | 89 | Let's Encrypt YE2 |
| lifolo.com www.lifolo.com  | 2027-01-18 | 89 | Let's Encrypt YE1 |

### nginx যা দেখায় (SNI ধরে, 127.0.0.1:443)
| নাম | মেয়াদ শেষ | যাচাই (নাম + chain) |
|---|---|---|
| lifolo.com | 2027-01-18 | 0 ok |
| www.lifolo.com | 2027-01-18 | 0 ok |
| api.lifolo.com | 2027-01-17 | 0 ok |
| <vps-ip>.sslip.io | 2027-01-17 | 0 ok |

ভুল নাম (SNI unknown.invalid): handshake বাতিল (ভালো)
SNI ছাড়া (IP দিয়ে স্ক্যান): handshake বাতিল (ভালো)
default_server (443): 2 · ssl_reject_handshake: 1
TLS 1.1: চলে না (ভালো) · TLS 1.3: চলে

### certbot
```
certbot.timer: active/enabled; শেষ চলা: Tue 2026-10-20 00:58:15 UTC; ফল: success
পরের চলা: Tue 2026-10-20 17:26:58 UTC
<vps-ip>.sslip.io: authenticator=webroot webroot=/var/www/letsencrypt deploy-hook=systemctl reload nginx
api.lifolo.com: authenticator=webroot webroot=/var/www/letsencrypt deploy-hook=systemctl reload nginx
lifolo.com: authenticator=webroot webroot=/var/www/letsencrypt deploy-hook=systemctl reload nginx

certbot renew --dry-run: exit 0 (25 সেকেন্ড)
  Congratulations, all simulated renewals succeeded: 
    /etc/letsencrypt/live/<vps-ip>.sslip.io/fullchain.pem (success)
    /etc/letsencrypt/live/api.lifolo.com/fullchain.pem (success)
    /etc/letsencrypt/live/lifolo.com/fullchain.pem (success)
```

### নিরাপত্তা-হেডার
| পথ | কোথা দিয়ে | HTTP | HSTS | nosniff | X-Frame / frame-ancestors | Referrer-Policy | CSP | cf-ray |
|---|---|---|---|---|---|---|---|---|
| lifolo.com/ | Cloudflare | 200 | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| lifolo.com/ | সরাসরি VPS | 200 | ✅ | ✅ | ✅ | ✅ | ✅ | ❌ |
| lifolo.com/admin/ | Cloudflare | 200 | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| lifolo.com/admin/ | সরাসরি VPS | 200 | ✅ | ✅ | ✅ | ✅ | ✅ | ❌ |
| lifolo.com/api/health | Cloudflare | 200 | ✅ | ✅ | ✅ | ❌ | ❌ | ✅ |
| lifolo.com/api/health | সরাসরি VPS | 200 | ✅ | ✅ | ✅ | ❌ | ❌ | ❌ |
| api.lifolo.com/api/health | Cloudflare | 200 | ✅ | ✅ | ✅ | ✅ | ❌ | ✅ |
| api.lifolo.com/api/health | সরাসরি VPS | 200 | ✅ | ✅ | ✅ | ✅ | ❌ | ❌ |
| lifolo.com/privacy | Cloudflare | 200 | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| lifolo.com/privacy | সরাসরি VPS | 200 | ✅ | ✅ | ✅ | ✅ | ✅ | ❌ |

HSTS (Cloudflare দিয়ে lifolo.com): `max-age=31536000`

### Cloudflare
- http://lifolo.com → 301 https://lifolo.com/x (301/308 → https হওয়া উচিত)
- https://www.lifolo.com/x → 301 https://lifolo.com/x
- Cloudflare-এর (ব্রাউজার যা দেখে) সার্টিফিকেট: issuer=C = US, O = Let's Encrypt, CN = YE2 notAfter=Jan 17 15:11:10 2027 GMT 
- Cloudflare দিয়ে lifolo.com/api/health: `{"ok":true,"firebase":true,"db":true} HTTP 200`
- Cloudflare দিয়ে api.lifolo.com/api/health: `{"ok":true,"firebase":true,"db":true} HTTP 200`
- lifolo.com-এর DNS: 104.21.84.125 172.67.193.4  (Cloudflare-এর ঠিকানা — proxy চালু)
- origin-এর পোর্ট 80 (/api/health): api.lifolo.com → 301, lifolo.com → 301; VPS-এ Cloudflare-এর ঠিকানা থেকে খোলা সংযোগ: 443-এ 25টা, 80-এ 16টা (80-এরগুলো http:// দর্শক আর Let's Encrypt-এর যাচাই)
- **Cloudflare → origin HTTPS-এ আসে** (80 শুধু https-এ পাঠায়, তবু Cloudflare দিয়ে JSON 200) → মোড Full বা Full (strict), Flexible নয় ✅
- Full (strict)-এর শর্ত: ওপরের "nginx যা দেখায়" সারণিতে lifolo.com, www, api-এর যাচাই `0 ok` হলে strict চালু থাকলেও চলে। ঠিক কোন মোড বাছা আছে তা শুধু Cloudflare ড্যাশবোর্ডে দেখা যায় (SSL/TLS → Overview) — এখানে কোনো Cloudflare API চাবি নেই।
- সরাসরি VPS (Cloudflare ছাড়া) বাইরে থেকে খোলা: 200 (GitHub-এর মেশিন থেকে) — Cloudflare-এর WAF/সীমা এড়িয়ে যাওয়া যায়; পরামর্শ deploy/OPS-DRILLS.md-এ।

_run 39372210461 · গাইড: deploy/OPS-DRILLS.md_
