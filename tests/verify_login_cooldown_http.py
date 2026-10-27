"""Writes only to an isolated 8832 server and waits a real 60 seconds."""
import os,re,time,uuid
from http_client import Session
base=os.environ.get('ROAM_BASE')
assert base=='http://127.0.0.1:8832', 'Use the isolated test server on port 8832 only'
name='Cooldown'+uuid.uuid4().hex[:12]; missing='Missing'+uuid.uuid4().hex[:12]
password='test-Password-123!'; changed='changed-Password-456!'; second='Second'+uuid.uuid4().hex[:12]
def fresh():
 s=Session(); r=s.get(base+'/api/session'); assert r.status_code==200; return s,r.json()['csrf']
def post(s,csrf,path,data,xff='1.1.1.1'):
 return s.request('POST',base+path,json=data,headers={'X-CSRF-Token':csrf,'X-Forwarded-For':xff})
def attempt(user,pw,xff='1.1.1.1'):
 s,csrf=fresh();return post(s,csrf,'/api/auth/login',{'username':user,'password':pw},xff)
def contract(r,scope):
 d=r.json();assert r.status_code==429 and d['status']==429 and d['code']=='LOGIN_COOLDOWN' and d['retry_after_seconds']>0 and d['cooldown_scope']==scope,(r.status_code,r.text)
 assert r.headers['Retry-After']==str(d['retry_after_seconds']);assert re.fullmatch(r'[a-f0-9-]{36}',d['request_id']);assert r.headers['X-Request-ID']==d['request_id'];return d
s,csrf=fresh();r=post(s,csrf,'/api/auth/register',{'username':name,'password':password});assert r.status_code==200,r.text
s_second,second_csrf=fresh();r_second=post(s_second,second_csrf,'/api/auth/register',{'username':second,'password':password});assert r_second.status_code==200,r_second.text
csrf=r.json()['csrf'];r=post(s,csrf,'/api/user/password',{'old_password':password,'new_password':changed});assert r.status_code==200,r.text
# Failed login while authenticated does not destroy the existing session.
csrf=r.json()['csrf'];r=post(s,csrf,'/api/auth/login',{'username':name,'password':'wrong'});assert r.status_code==401;assert s.get(base+'/api/session').json()['user']['username']==name
for i in range(4):
 r=attempt(name.upper() if i%2 else name,'wrong',str(i)+'.1.1.1');assert r.status_code==(401 if i<3 else 429),(i,r.status_code,r.text)
d=contract(r,'pair');assert d['retry_after_seconds']==60
r=attempt(name,changed,'99.98.97.96');contract(r,'pair')
time.sleep(61);r=attempt(name,changed);assert r.status_code==200,(r.status_code,r.text)
# Correct credentials clear only this name's failure count.
for _ in range(4):assert attempt(name,'wrong').status_code==401
assert attempt(name,changed).status_code==200
for _ in range(4):assert attempt(name,'wrong').status_code==401
contract(attempt(name,'wrong'),'pair')
# Absent names get the same errors and threshold as existing ones.
for _ in range(4):
 r=attempt(missing,'wrong');assert r.status_code==401 and r.json()['message']=='用户名或密码不正确'
contract(attempt(missing,'wrong'),'pair')
# 19 failures so far. Bring source to 29 using distinct names, then succeed;
# success must not erase source total. The next wrong name triggers source cooldown.
for i in range(10):assert attempt('Other'+str(i)+uuid.uuid4().hex[:6],'wrong').status_code==401
assert attempt(second,password).status_code==200
other,token=fresh();r=post(other,token,'/api/auth/login',{'username':name+'x','password':'wrong'})
# The preceding call is failure number 30; the successful login before it must use
# another registered account, since the original pair is cooling down.
contract(r,'source')
print('PASS actual HTTP: registration, password change, session preservation, case, XFF, 429, real expiry, pair reset, absent-name equivalence, source threshold')
