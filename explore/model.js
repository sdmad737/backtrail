/** Explore-only adapters. No live location, camera, inference, or notification APIs are invoked. */
export const route = [
 [85,398],[97,368],[128,346],[153,323],[142,290],[156,264],[191,248],[221,260],
 [250,238],[244,205],[267,184],[283,153],[271,125],[296,98],[289,69],[307,42]
].map(([x,y], i) => ({id:`sample-${i}`,segmentId:'segment-a',x,y,latitude:37.89+(440-y)*.000009,longitude:-122.58+x*.0000114,fixTime:Date.parse('2026-10-24T17:00:00Z')+i*60000,accuracy:6,provenance:'replay'}));
export const fixtureLandmarks = [
 {id:'trailhead',label:'Trailhead',note:'Started here. The car is near the entrance.',image:'assets/redwood-creek.jpg',index:0,description:'Example creek photograph; the fictional trailhead location comes from the replay.',source:'NPS · illustrative photo'},
 {id:'stone-bridge',label:'Stone bridge',note:'Left my orange flask beside the bridge. Pick it up on the way back.',image:'assets/stone-bridge.jpg',index:5,description:'A low stone arch over a creek, with autumn trees and boulders.',source:'NPS · illustrative photo'},
 {id:'log-bridge',label:'Fern crossing',note:'Narrow log bridge with a wooden handrail on the left.',image:'assets/log-bridge.jpg',index:11,description:'A narrow mossy wooden bridge with a single handrail, surrounded by ferns.',source:'NPS / Jon Preston · illustrative photo'}
];
export function distance(a,b) { if(!a||!b)return null; const p=Math.PI/180,R=6371000,dlat=(b.latitude-a.latitude)*p,dlon=(b.longitude-a.longitude)*p;const v=Math.sin(dlat/2)**2+Math.cos(a.latitude*p)*Math.cos(b.latitude*p)*Math.sin(dlon/2)**2;return 2*R*Math.atan2(Math.sqrt(v),Math.sqrt(1-v)); }
export function bearing(a,b){const p=Math.PI/180,y=Math.sin((b.longitude-a.longitude)*p)*Math.cos(b.latitude*p),x=Math.cos(a.latitude*p)*Math.sin(b.latitude*p)-Math.sin(a.latitude*p)*Math.cos(b.latitude*p)*Math.cos((b.longitude-a.longitude)*p);return (Math.atan2(y,x)/p+360)%360;}
export function angleDelta(a,b){return ((a-b+540)%360)-180;}
export function routeDistance(from,to=0){let m=0;for(let i=Math.min(from,to)+1;i<=Math.max(from,to);i++)if(route[i].segmentId===route[i-1].segmentId)m+=distance(route[i],route[i-1]);return m;}
export function createState(){return {version:1,id:'explore-outing',source:'replay',status:'ready',view:'map',position:0,recordedThrough:0,heading:325,quality:'good',maps:true,dark:false,large:false,landmarks:[{...fixtureLandmarks[0]}],guidanceMode:'none',selectedId:null,retraceIndex:null,reminders:[],notifications:[],queryState:'idle',queryResults:[],query:'',queryRequest:0,overlay:null,toast:'',cameraState:'preview',modelState:'sample',history:[]};}
export function target(state){ if(state.guidanceMode==='find_landmark')return state.landmarks.find(x=>x.id===state.selectedId)??null; if(state.guidanceMode==='retrace'&&state.retraceIndex!==null){const i=state.retraceIndex;return state.landmarks.find(x=>x.index===i)??{id:route[i].id,label:i===0?'Trailhead':'Recorded trail',index:i,image:nearestPhoto(state,i).image,description:'Follow the ordered samples you recorded.'};}return null;}
export function nearestPhoto(state,index){return [...state.landmarks].sort((a,b)=>Math.abs(a.index-index)-Math.abs(b.index-index))[0];}
export function guidance(state){const t=target(state);if(!t)return null;const valid=state.quality==='good';const straight=distance(route[state.position],route[t.index]);return {target:t,valid,bearing:valid?bearing(route[state.position],route[t.index]):null,distance:valid?straight:null,remaining:valid?(state.guidanceMode==='retrace'?straight+routeDistance(t.index):straight):null,semantics:state.guidanceMode==='retrace'?'along recorded trail':'straight-line to saved place',relative:valid?angleDelta(bearing(route[state.position],route[t.index]),state.heading):null,near:valid&&straight<55};}
function evaluateReminders(s){for(const task of s.reminders){if(task.state==='collected'||task.state==='notified')continue;const m=s.landmarks.find(x=>x.id===task.landmarkId);if(m.locationKnown===false)continue;const d=distance(route[s.position],route[m.index]);if(s.quality!=='good'){task.nearFixes=0;continue;}if(task.state==='waiting_to_leave'&&d>120){task.awayFixes=(task.awayFixes||0)+1;if(task.awayFixes>=2)task.state='armed';}else if(task.state==='armed'){task.nearFixes=d<70?(task.nearFixes||0)+1:0;if(task.nearFixes>=2){task.state='notified';s.notifications.push({id:task.id,title:task.label,body:`Last recorded at ${m.label}. Check the original photo.`,sample:true});s.toast='Sample arrival reminder · check your flask';}}}}
export function reduce(s,action){s=structuredClone(s);s.toast='';switch(action.type){
 case 'start':s.status='recording';s.guidanceMode='none';s.history.push('User started replay outing');break;
 case 'load':s.guidanceMode='none';s.retraceIndex=null;s.selectedId=null;s.notifications=[];s.queryRequest++;s.status='recording';s.position=15;s.recordedThrough=15;s.landmarks=structuredClone(fixtureLandmarks);s.reminders=[{id:'pickup-flask',landmarkId:'stone-bridge',label:'Pick up your flask',state:'armed',nearFixes:0}];s.history.push('Loaded full sample outing');break;
 case 'step':if(s.status==='ready'||s.status==='ended'||s.status==='paused')break;if(s.quality!=='good'){s.toast='Movement paused: location is uncertain. Route and photos remain available.';break;}if(s.guidanceMode==='retrace'){s.position=Math.max(0,s.position-1);if(s.position<=s.retraceIndex)s.retraceIndex=Math.max(0,s.retraceIndex-1);if(s.position===0){s.status='arrived';s.toast='Back at the sample trailhead';}}else if(s.guidanceMode==='find_landmark'){const m=target(s);if(m)s.position+=Math.sign(m.index-s.position);}else{s.position=Math.min(15,s.position+1);s.recordedThrough=Math.max(s.recordedThrough,s.position);for(const m of fixtureLandmarks)if(m.index===s.position&&!s.landmarks.some(x=>x.id===m.id)){s.landmarks.push({...m});s.toast=`Sample memory added · ${m.label}`;}}evaluateReminders(s);break;
 case 'fix':evaluateReminders(s);break;
 case 'pause':s.status=s.status==='paused'?'recording':'paused';break;
 case 'end':s.status='ended';s.guidanceMode='none';s.overlay='complete';break;
 case 'capture':s.overlay='capture';break;
 case 'save':{const id=`saved-${s.landmarks.length}`,item={id,label:action.label||'Saved place',note:action.note||'',image:action.image||'assets/log-bridge.jpg',index:s.position,description:'Sample interpretation pending. Your exact note and selected example photo are preserved.',source:'NPS · example photo',locationKnown:s.quality==='good'};s.landmarks.push(item);s.overlay=null;s.toast='Place saved in this Explore session';if(action.pickup)s.reminders.push({id:`pickup-${id}`,landmarkId:id,label:action.note||'Collect saved item',state:'waiting_to_leave',nearFixes:0});break;}
 case 'find':if(s.landmarks.some(m=>m.id===action.id&&m.locationKnown!==false)){s.selectedId=action.id;s.guidanceMode='find_landmark';s.overlay=null;s.view=action.view||'ar';}break;
 case 'retrace':if(s.recordedThrough>0){s.status='retracing';s.guidanceMode='retrace';if(s.retraceIndex===null)s.retraceIndex=Math.max(0,s.position-1);s.overlay=null;s.toast='Following your recorded route in reverse';}break;
 case 'recognize':{const g=guidance(s);if(g?.near){s.overlay='recognize';}else{s.toast='Get closer using the replay steps, then compare the original photo.';}break;}
 case 'collect':{const t=s.reminders.find(x=>x.landmarkId===target(s)?.id);if(t)t.state='collected';s.overlay=null;s.toast='Pickup marked collected by you';break;}
 case 'quality':s.quality=action.value;break;
 case 'view':s.view=action.value;break;
 case 'heading':s.heading=Number(action.value);break;
 case 'overlay':s.overlay=action.value;break;
 case 'maps':s.maps=!s.maps;break;
 case 'dark':s.dark=!s.dark;break;
 case 'large':s.large=!s.large;break;
 case 'camera':s.cameraState=action.value;break;
 case 'queryStart':s.query=action.query;s.queryState='loading';s.queryResults=[];s.queryRequest++;break;
 case 'queryDone':if(action.request!==s.queryRequest)break;s.queryResults=action.ids.filter(id=>s.landmarks.some(m=>m.id===id));s.queryState=action.error?'error':s.queryResults.length?'success':'empty';break;
 case 'reset':return createState();
 }return s;}
export class ReplayAdapter {constructor(onChange){this.state=createState();this.onChange=onChange;}dispatch(a){this.state=reduce(this.state,a);this.onChange(this.state);} }
export class SampleMemoryAdapter {async find(query,landmarks,{fail=false}={}){await new Promise(r=>setTimeout(r,650));if(fail)throw Error('Model unavailable');const q=query.toLowerCase();const tests=[[/flask|bottle|water|left|orange/,'stone-bridge'],[/fern|wood|narrow|moss|handrail|crossing/,'log-bridge'],[/car|start|entrance|trailhead/,'trailhead'],[/stone|arch|creek|bridge/,'stone-bridge']];const known=tests.find(([p])=>p.test(q));if(known)return landmarks.filter(m=>m.id===known[1]).map(m=>m.id);const terms=q.split(/\W+/).filter(x=>x.length>3);return landmarks.filter(m=>terms.some(t=>`${m.label} ${m.note}`.toLowerCase().includes(t))).map(m=>m.id);}}
