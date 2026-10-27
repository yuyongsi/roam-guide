"""Destructive only to an isolated ROAM_BASE database; waits for a real 60-second cooldown."""
import os,re,time,uuid
from http_client import Session
base=os.environ.get('ROAM_BASE')
assert base and base.endswith(':8832'), 'Use the isolated test server on port 8832 only'
name='Cooldown'+uuid.uuid4().hex[:12]; password='test-Password-123!'
def fresh():
 s=Session(); r=s.get(base+'/api/session'); assert r.status_code==200; return s,r.json()['csrf']
def post(s,csrf,path,data,xff='1.1.1.1'):
 return s.request('POST',base+path,json=data,headers={'X-CSRF-Token':csrf,'X-Forwarded-For':xff})
s,csrf=fresh();r=post(s,csrf,'/api/auth/register',{'username':name,'password':password});assert r.status_code==200,r.text
sessions=[fresh() for _ in range(5)]
for i,(client,token) in enumerate(sessions):
 r=post(client,token,'/api/auth/login',{'username':name.upper() if i%2 else name,'password':'wrong'},str(i)+'.1.1.1')
 assert r.status_code==(401 if i<4 else 429),(i,r.status_code,r.text)
data=r.json(); assert data['status']==429 and data['code']=='LOGIN_COOLDOWN' and data['retry_after_seconds']==60 and data['cooldown_scope']=='pair';assert r.headers['Retry-After']=='60';assert re.fullmatch(r'[a-f0-9-]{36}',data['request_id']);assert r.headers['X-Request-ID']==data['request_id']
s2,token=fresh();r=post(s2,token,'/api/auth/login',{'username':name,'password':password},'99.98.97.96');assert r.status_code==429 and r.json()['retry_after_seconds']>0
start=time.monotonic();time.sleep(61);r=post(s2,token,'/api/auth/login',{'username':name,'password':password});assert r.status_code==200,(r.status_code,r.text);assert time.monotonic()-start>=60
print('PASS actual HTTP pair threshold, case folding, sessions, XFF ignored, 429 contract, correct password blocked, real expiry')
