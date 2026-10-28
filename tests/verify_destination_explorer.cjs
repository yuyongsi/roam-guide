const fs=require('fs'),vm=require('vm'),path=require('path'),assert=require('assert');
let checks=0;const check=(value,message)=>{assert(value,message);checks++;};
const defer=()=>{let resolve,reject;const promise=new Promise((a,b)=>{resolve=a;reject=b;});return {promise,resolve,reject};};
const cities=[{id:1,slug:'lake',name:'湖城',province:'甲省',tagline:'沿湖走走',description:'湖边的城市',spot_count:8,guide_count:2,image:'/assets/lake.jpg'},{id:2,slug:'old-town',name:'古城',province:'乙省',tagline:'认识街巷',description:'城里的日常',spot_count:4,guide_count:1,image:'/assets/town.jpg'}];
const elements=new Map(),focused=[];
function element(){return {innerHTML:'',textContent:'',dataset:{context:'1'},attributes:{},setAttribute(k,v){this.attributes[k]=v;},removeAttribute(k){delete this.attributes[k];}};}
for(const id of ['destinationExplorer','destinationCityIndex','destinationCityFeature','destinationSpotPreview','destinationTravelNotes','destinationMatchCount','destinationExplorerStatus'])elements.set(id,element());
const themes=['','山水风景','街巷生活','人文历史'],themeButtons=themes.map(value=>({...element(),dataset:{value}})),cityButtons=cities.map(city=>({...element(),dataset:{value:city.slug},focus(){focused.push(city.slug);}}));
const location={pathname:'/destinations',href:'http://localhost/destinations'};
const state={renderId:7,cities};let fetcher;
const escape=value=>String(value??'').replace(/[&<>"']/g,ch=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[ch]));
const context=vm.createContext({state,location,URL,Map,Promise,travelThemes:themes.slice(1),icon:()=>'',esc:escape,grid:spots=>spots.map(s=>escape(s.title)).join(''),api:url=>fetcher(url),history:{state:null,replaceState(s,title,url){location.href='http://localhost'+url;}},document:{getElementById:id=>elements.get(id),querySelectorAll:selector=>selector.includes('theme')?themeButtons:cityButtons}});
vm.runInContext(fs.readFileSync(path.join(__dirname,'../src/main/resources/static/js/destination-explorer.js'),'utf8'),context);
const action=vm.runInContext('destinationExplorerAction',context);
const allRows=cities.map(city=>({city,total:city.spot_count,spots:[{title:city.name+'景点'}]}));
function fresh(){state.renderId=7;location.pathname='/destinations';location.href='http://localhost/destinations';for(const el of elements.values()){el.innerHTML='';el.textContent='';el.attributes={};}const c={token:1,renderId:7,cities,theme:'',selected:'lake',cache:new Map(),rows:allRows,request:0};context.fixtureContext=c;vm.runInContext('destinationExplorerUi.context=fixtureContext',context);return c;}
(async()=>{
 let c=fresh(),calls=[];
 fetcher=async url=>{calls.push(url);const city=Number(new URL('http://localhost'+url).searchParams.get('city'));return {total:city===1?2:0,items:city===1?[{title:'湖边'}]:[]};};
 await action({dataset:{destination:'theme',value:'山水风景'}});
 check(calls.length===2&&calls.every(url=>new URL('http://localhost'+url).searchParams.get('theme')==='山水风景'),'主题与城市条件交给实际搜索接口');
 check(c.theme==='山水风景'&&elements.get('destinationMatchCount').textContent.startsWith('1 '),'城市匹配数量依据完整结果总数');
 check(elements.get('destinationCityIndex').innerHTML.includes('disabled')&&elements.get('destinationCityFeature').innerHTML.includes('湖城'),'不匹配城市停用，主预览保持可用城市');
 const requestCount=calls.length;await action({dataset:{destination:'theme',value:'山水风景'}});
 check(calls.length===requestCount,'同一页面内复用已完成的主题结果');

 c=fresh();const old=defer(),latest=defer();
 fetcher=url=>new URL('http://localhost'+url).searchParams.get('theme')==='山水风景'?old.promise:latest.promise;
 const first=action({dataset:{destination:'theme',value:'山水风景'}}),second=action({dataset:{destination:'theme',value:'人文历史'}});
 latest.resolve({total:1,items:[{title:'新主题'}]});await second;old.resolve({total:2,items:[{title:'过期主题'}]});await first;
 check(c.theme==='人文历史'&&elements.get('destinationSpotPreview').innerHTML.includes('新主题')&&!elements.get('destinationSpotPreview').innerHTML.includes('过期主题'),'晚到的旧请求不能覆盖新主题');

 c=fresh();const leaving=defer();fetcher=()=>leaving.promise;
 const pending=action({dataset:{destination:'theme',value:'山水风景'}});state.renderId++;location.pathname='/food';elements.get('destinationCityFeature').innerHTML='OTHER PAGE';
 leaving.resolve({total:1,items:[]});await pending;
 check(elements.get('destinationCityFeature').innerHTML==='OTHER PAGE','离开页面后不能提交旧预览');

 c=fresh();fetcher=async()=>{throw new Error('offline');};elements.get('destinationCityFeature').innerHTML='KEEP PREVIEW';
 await action({dataset:{destination:'theme',value:'街巷生活'}});
 check(c.theme===''&&elements.get('destinationCityFeature').innerHTML==='KEEP PREVIEW','请求失败保留原有城市与主题');
 check(!c.cache.has('街巷生活')&&!elements.get('destinationExplorer').attributes['aria-busy'],'失败结果不进入缓存并释放加载状态');
 fetcher=async()=>({total:1,items:[]});await action({dataset:{destination:'theme',value:'街巷生活'}});
 check(c.theme==='街巷生活','失败后可直接重试同一主题');

 c=fresh();const background=defer();fetcher=()=>background.promise;
 const filtering=action({dataset:{destination:'theme',value:'山水风景'}});await action({dataset:{destination:'city',value:'old-town'}});background.resolve({total:1,items:[]});await filtering;
 check(c.theme===''&&c.selected==='old-town'&&focused.at(-1)==='old-town','切换城市取消待完成筛选并保留键盘焦点');
 check(new URL(location.href).searchParams.get('city')==='old-town','城市选择保存在可重新打开的网址中');

 c=fresh();fetcher=async()=>({total:0,items:[]});await action({dataset:{destination:'theme',value:'山水风景'}});
 check(c.selected===''&&elements.get('destinationCityFeature').innerHTML.includes('查看全部目的地')&&elements.get('destinationSpotPreview').innerHTML==='','没有匹配城市时提供恢复入口');
 c=fresh();c.rows=[{...allRows[0],city:{...cities[0],description:'<img onerror=alert(1)>'}}];context.fixtureContext=c;
 check(vm.runInContext('destinationExplorerFeature(fixtureContext)',context).includes('&lt;img onerror=alert(1)&gt;'),'城市内容仍经过转义');
 console.log('PASS '+checks+' destination explorer assertions');
})().catch(error=>{console.error(error);process.exitCode=1;});
