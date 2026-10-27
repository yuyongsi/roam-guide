import urllib.request,urllib.error,urllib.parse,http.cookiejar,json as jsonlib,uuid
class Response:
 def __init__(self,r):
  self.status_code=r.code;self.content=r.read();self.text=self.content.decode('utf-8',errors='replace');self.headers=r.headers
 def json(self):return jsonlib.loads(self.text)
 def raise_for_status(self):
  if self.status_code>=400:raise RuntimeError(str(self.status_code)+self.text[:200])
class Session:
 def __init__(self):self.opener=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
 def request(self,method,url,json=None,files=None,headers=None,timeout=20):
  headers=dict(headers or {});data=None
  if files:
   boundary='qa'+uuid.uuid4().hex;parts=[]
   for name,(filename,f,mime) in files.items():
    parts.extend([('--'+boundary+'\r\nContent-Disposition: form-data; name=\"'+name+'\"; filename=\"'+filename+'\"\r\nContent-Type: '+mime+'\r\n\r\n').encode(),f.read(),b'\r\n'])
   parts.append(('--'+boundary+'--\r\n').encode());data=b''.join(parts);headers['Content-Type']='multipart/form-data; boundary='+boundary
  elif json is not None:data=jsonlib.dumps(json).encode('utf-8');headers['Content-Type']='application/json'
  req=urllib.request.Request(urllib.parse.quote(url,safe=':/?&=%+-._~'),data=data,headers=headers,method=method)
  try:r=self.opener.open(req,timeout=timeout)
  except urllib.error.HTTPError as e:r=e
  return Response(r)
 def get(self,url,**kwargs):return self.request('GET',url,**kwargs)
class requests:
 Session=Session
 @staticmethod
 def get(url,**kwargs):return Session().get(url,**kwargs)

# 基线检查使用与新界面相同的版本请求；严格缺参/过期检查仍使用原始Session。
class VersionedSession(Session):
 def request(self,method,url,json=None,files=None,headers=None,timeout=20):
  import re
  path=urllib.parse.urlsplit(url).path
  parsed=urllib.parse.urlsplit(url);base=parsed.scheme+'://'+parsed.netloc
  body=dict(json)if isinstance(json,dict)else json
  key=None;resource=None
  match=re.fullmatch(r'/api/admin/(spots|guides|cities)/(\d+)',path)
  if method=='PUT'and match:
   key='revision';resource='/api/admin/'+match.group(1)
   if body is not None and key not in body:
    response=super().request('GET',base+resource,headers=headers,timeout=timeout)
    rows=response.json()if response.status_code==200 else[]
    body[key]=next((row['revision']for row in rows if row['id']==int(match.group(2))),1)
  match=re.fullmatch(r'/api/me/trips/(\d+)(/publish)?',path)
  if match and ((method=='DELETE'and not match.group(2))or(method=='POST'and match.group(2))):
   key='trip_revision'if match.group(2)else'revision'
   if body is None:body={}
   if isinstance(body,dict)and key not in body:
    response=super().request('GET',base+'/api/me/trips/'+match.group(1),headers=headers,timeout=timeout)
    body[key]=response.json().get('revision',1)if response.status_code==200 else 1
  return super().request(method,url,json=body,files=files,headers=headers,timeout=timeout)
requests.VersionedSession=VersionedSession
