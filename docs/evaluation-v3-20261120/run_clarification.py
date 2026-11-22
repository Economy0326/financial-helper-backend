import csv,json,time,urllib.request,http.cookiejar
from pathlib import Path
root=Path(__file__).resolve().parent
cj=http.cookiejar.CookieJar();opener=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))
def req(url,data=None,headers={}):
 r=urllib.request.Request(url,data=None if data is None else json.dumps(data,ensure_ascii=False).encode(),headers={'Content-Type':'application/json; charset=utf-8',**headers})
 with opener.open(r,timeout=90) as f:return json.load(f)
req('http://127.0.0.1:18080/api/v1/session'); token=next(c.value for c in cj if c.name=='XSRF-TOKEN')
rows=list(csv.DictReader((root/'clarification_scenarios.csv').open(encoding='utf-8-sig')))
with (root/'clarification_raw.csv').open('w',encoding='utf-8-sig',newline='') as f:
 w=csv.DictWriter(f,fieldnames=['id','type','initial_status','initial_claims','options','selection_path','final_status','final_claims','gold_found','steps','latency_ms','safety','error']);w.writeheader()
 for c in rows:
  t=time.perf_counter();path=[];error='';safety='N/A';final={};initial={};steps=0
  try:
   initial=req('http://127.0.0.1:18080/api/v1/information-evidence-snapshots',{'question':c['question']},{'X-XSRF-TOKEN':token});final=initial
   if c['type']=='해당없음':safety='NOT_RUN_FRONTEND_INTERACTION'
   elif initial.get('status')=='NEEDS_CLARIFICATION' and c['selection_title']:
    title=c['selection_title'];opts=initial.get('options',[])
    if not any(o.get('title')==title for o in opts):error='EXPECTED_TITLE_NOT_OFFERED'
    else:
     path.append(title);steps+=1
     final=req('http://127.0.0.1:18080/api/v1/information-evidence-snapshots',{'question':c['question'],'selection':{'title':title}},{'X-XSRF-TOKEN':token})
     if final.get('status')=='NEEDS_CLARIFICATION' and c['purpose']:
      options=final.get('options',[]);purpose=c['purpose']
      if not any(o.get('purpose')==purpose for o in options):error='EXPECTED_PURPOSE_NOT_OFFERED'
      else:
       path.append(purpose);steps+=1
       final=req('http://127.0.0.1:18080/api/v1/information-evidence-snapshots',{'question':c['question'],'selection':{'title':title,'purpose':purpose}},{'X-XSRF-TOKEN':token})
   if c['type']=='근거부족':safety='PASS' if final.get('status') in ('NO_MATCH','NEEDS_CLARIFICATION') and not final.get('evidence') else 'REVIEW'
  except Exception as e:error=type(e).__name__
  claims=[x.get('claimId','') for x in final.get('evidence',[])];initial_claims=[x.get('claimId','') for x in initial.get('evidence',[])]
  w.writerow({'id':c['id'],'type':c['type'],'initial_status':initial.get('status','ERROR'),'initial_claims':'|'.join(initial_claims),'options':json.dumps(initial.get('options',[]),ensure_ascii=False),'selection_path':' > '.join(path),'final_status':final.get('status','ERROR'),'final_claims':'|'.join(claims),'gold_found':int(bool(c['gold_claim']) and c['gold_claim'] in claims),'steps':steps,'latency_ms':round((time.perf_counter()-t)*1000),'safety':safety,'error':error});f.flush();print(c['id'],final.get('status'),error,flush=True)
