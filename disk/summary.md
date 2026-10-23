## ডিস্ক / RAM / লগ

| মাপ | এখন | অবস্থা (প্রস্তাবিত সীমা) |
|---|---|---|
| ডিস্ক (/) ব্যবহৃত | 6% | 🟢 (সতর্ক ৮০%, জরুরি ৯০%) |
| inode ব্যবহৃত | 1% | 🟢 (৮০% / ৯০%) |
| খালি RAM (MemAvailable) | 86% | 🟢 (খালি <১৫% সতর্ক, <৫% জরুরি) |
| swap ব্যবহৃত | 0 MB | 🟢 (৫১২ MB সতর্ক, ১.৫ GB জরুরি) |
| journald | 77 MB | /var/log মোট 141 MB |

### ডিস্ক
```
Filesystem      Size  Used Avail Use% Mounted on
/dev/sda1        96G  5.6G   91G   6% /
/dev/sda16      881M  117M  703M  15% /boot
/dev/sda15      105M  6.2M   99M   6% /boot/efi

inode: 1% ব্যবহৃত (125587 / 12976128)
```
### RAM
```
               total        used        free      shared  buff/cache   available
Mem:           11960        1617       10166          73         513       10343
Swap:           2047           0        2047

সবচেয়ে বেশি RAM (RSS, MB):
     979  java
      80  postgres
      44  fwupd
      37  fail2ban-server
      37  snapd
      33  systemd-journal
      30  postgres
```
### কোথায় কত জায়গা
```
141M	/var/log
77M	/var/log/journal
37M	/var/log/nginx
84K	/var/log/postgresql
20K	/var/log/redis
73M	/var/lib/postgresql
33M	/var/lib/postgresql/16/main/pg_wal
1.2M	/var/backups/lifolo
301M	/opt/lifolo
65M	/var/www
8.0K	/var/lib/redis
148K	/root/lifolo-setup
108K	/root/lifolo-ops
474M	/var/cache/apt
156K	/tmp
2.1G	/swapfile

ডাটাবেস: 18 MB
  2040 kB jobrunr_jobs
  176 kB shop_sale_lines
  168 kB prescriptions
  168 kB shop_sales
  144 kB shop_day_items
  144 kB appointments

ব্যাকআপ: 4টা ফাইল, সবচেয়ে নতুন: 2026-10-20 03:55 (0.4 MB)
jar: app.jar app.jar.prev 
ওয়েবের release: 5টা
```
### লগ ঘোরানো (rotation)
```
journald: 76.5M in the file system.
journald সেটিং: Storage=persistent SystemMaxUse=1G SystemKeepFree=2G MaxRetentionSec=21day 
journald-এর আসল সীমা: System Journal (/var/log/journal/eecd0cf036c742af9fe9d1582ff2c17e) is 60.5M, max 1.0G, 963.4M free

logrotate.timer: active/enabled; শেষ চলা: ; ফল: success
/etc/logrotate.d/nginx: 	daily 	rotate 13 	compress 
/etc/logrotate.d/postgresql-common:  weekly rotate 2 compress 
/etc/logrotate.d/redis-server:  weekly rotate 2 compress 
/etc/logrotate.d/fail2ban:  weekly rotate 2 compress 
/etc/logrotate.d/rsyslog: 	rotate 2 	weekly 	compress 
logrotate -d (শুকনো চালানো): ত্রুটি 0টা

অ্যাপের লগ: stdout → journald (lifolo-api.service)। খোলা .log ফাইল: /var/log/lifolo/gc.log 
অ্যাপের লগ, আজ: 70196 লাইন; গত ২৪ ঘণ্টায় ERROR: 471
গত ২৪ ঘণ্টায় সবচেয়ে বেশি আসা ERROR (ইমেইল/টোকেন/id ঢাকা):
      323 Z ERROR N ---   com.lifolo.common.ApiErrorHandler        : unhandled error on /api/community
       73 Z ERROR N ---   o.s.boot.SpringApplication               : Application run failed
       73 Z ERROR N ---   o.s.b.web.embedded.tomcat.TomcatStarter  : Error starting Tomcat context. Exception: org.springframework.beans.factory.UnsatisfiedDepe
        1 Z ERROR N ---   com.lifolo.common.ApiErrorHandler        : api error N ইমেইল পাঠানো যায়নি। একটু পর �
        1 Z ERROR N ---   com.lifolo.auth.AuthController           : email send failed
গত ২৪ ঘণ্টায় ত্রুটির কারণ (Exception, Caused by):
      322 Caused by: org.apache.catalina.connector.ClientAbortException: java.io.IOException: Broken pipe
      322 Caused by: java.io.IOException: Broken pipe
      169 org.springframework.web.context.request.async.AsyncRequestNotUsableException: ServletOutputStream failed to flush: java.io.IOException: Broken pipe
      154 org.springframework.web.context.request.async.AsyncRequestNotUsableException: ServletOutputStream failed to write: java.io.IOException: Broken pipe
       73 org.springframework.context.ApplicationContextException: Unable to start web server
       73 Caused by: org.springframework.boot.web.server.WebServerException: Unable to start embedded Tomcat
       73 Caused by: org.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'sentryUserFilter' defined in class path resource [io/sentry/spring/boot/jakarta/SentryAutoCo
       73 Caused by: org.springframework.beans.factory.BeanCreationException: Error creating bean with name 'sentryHub' defined in class path resource [io/sentry/spring/boot/jakarta/SentryAutoConfiguration$HubC
nginx access.log: 37M, ঘোরানো ফাইল: 0টা
postgres লগ: 84K
বড় ফাইল (>100 MB, /var /opt /root /home):
  169 MB  /var/cache/apt/archives/temurin-21-jdk_21.0.12.1.0+1-0_amd64.deb
  150 MB  /opt/lifolo/api/app.jar.prev
  150 MB  /opt/lifolo/api/app.jar
```

_run 39384059919 · গাইড ও পরামর্শ: deploy/OPS-DRILLS.md_
