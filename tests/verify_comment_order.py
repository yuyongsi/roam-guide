"""执行实际Java查询字符串，复核编号与时间不一致及同一时刻的评论顺序。"""
from pathlib import Path
import re,sqlite3

ROOT=Path(__file__).resolve().parents[1]
db=sqlite3.connect(':memory:');db.row_factory=sqlite3.Row
db.executescript((ROOT/'sql/schema.sql').read_text(encoding='utf-8'))
db.execute("INSERT INTO users(id,username,password_hash) VALUES(1,'fixture','unused')")
db.execute("INSERT INTO cities(id,slug,name,province,tagline,description,image) VALUES(1,'fixture','city','province','short','intro','/fixture.jpg')")
db.execute("INSERT INTO guides(id,city_id,title,intro,image) VALUES(1,1,'guide','intro','/fixture.jpg')")
db.execute("INSERT INTO social_posts(id,user_id,category,title,body) VALUES(1,1,'提问','fixture','fixture body')")
times=['2026-10-26T10:00:00.000Z','2026-10-24T10:00:00.000Z','2026-10-25T10:00:00.000Z','2026-10-25T10:00:00.000Z']
for id,time in enumerate(times,1):
 db.execute('INSERT INTO comments(id,guide_id,user_id,body,created_at) VALUES(?,1,1,?,?)',(id,str(id),time))
 db.execute('INSERT INTO social_comments(id,post_id,user_id,body,created_at) VALUES(?,1,1,?,?)',(id,str(id),time))
queries=[]
for file in ['ApiController.java','SocialController.java']:
 source=(ROOT/'src/main/java/cn/edu/travel'/file).read_text(encoding='utf-8')
 for sql in re.findall(r'"(SELECT m\.[^"\n]+)"',source):
  if ('FROM comments m' in sql or 'FROM social_comments m' in sql)and'ORDER BY' in sql:queries.append(sql)
assert len(queries)==4,len(queries)
for query in queries:
 rows=db.execute(query,[1]*query.count('?')).fetchall()
 assert [r['id']for r in rows]==[1,4,3,2],query
print('PASS 4 actual queries: timestamps descending, ID only breaks ties')
