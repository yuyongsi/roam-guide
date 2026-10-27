"""只读冒烟检查，修改前可复现错误响应缺少统一格式的问题。"""
import os,json,re,urllib.request,urllib.error
BASE=os.environ.get('ROAM_BASE','http://127.0.0.1:8818')
checks=[]
for path,status,code in [('/api/spots/999999999',404,'NOT_FOUND'),('/api/spots/not-a-number',400,'INVALID_REQUEST'),('/api/search?page=abc',400,'INVALID_REQUEST'),('/api/admin/audit',401,'AUTH_REQUIRED')]:
 try:r=urllib.request.urlopen(BASE+path,timeout=10)
 except urllib.error.HTTPError as e:r=e
 data=json.loads(r.read())
 assert r.status==status,(path,r.status)
 assert data.get('code')==code,(path,'missing error code',data)
 assert re.fullmatch(r'[a-f0-9-]{36}',data.get('request_id','')),(path,'missing request id')
 assert data['request_id']==r.headers.get('X-Request-ID'),(path,'request id differs')
 assert data.get('message') and data.get('status')==status,(path,'missing message or status')
 checks.append(path)
print('PASS',len(checks),'read-only error contracts')
