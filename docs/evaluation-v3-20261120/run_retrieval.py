import csv, json, os, re, subprocess, time, urllib.request, urllib.error, http.cookiejar, statistics, math, base64
from pathlib import Path
ROOT=Path(__file__).resolve().parent
GEN='ae25453b-0d2c-46a2-9c9d-3234c6d7cd51'
def sql(q):
 p=subprocess.run(['docker','exec','-i','fh-official-review-mvp','psql','-U','fh_review_demo','-d','fh_review_demo','-At','-v','ON_ERROR_STOP=1'],input=q.encode('utf-8'),stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=30)
 if p.returncode: raise RuntimeError('PostgreSQL query failed')
 return p.stdout.decode('utf-8').strip()
def quoted(s):return "convert_from(decode('"+base64.b64encode(s.encode()).decode()+"','base64'),'UTF8')"
active=sql("SELECT retrieval_generation_id::text FROM active_retrieval_generation WHERE singleton_key=true")
if active != GEN: raise RuntimeError('Active generation changed; abort')
corpus=json.loads(sql("SELECT coalesce(json_agg(json_build_object('chunk',c.source_chunk_id,'claim',c.candidate_id,'title',d.title,'body',s.body) ORDER BY d.title,s.sequence),'[]') FROM source_chunk_indexing i JOIN source_document_claim c ON c.source_chunk_id=i.source_chunk_id JOIN source_chunk s ON s.id=c.source_chunk_id JOIN source_document d ON d.id=c.source_document_id WHERE i.retrieval_generation_id='"+GEN+"' AND i.indexing_status='READY' AND c.extractor_version='generic-claim-v3'"))
if len(corpus)!=41:raise RuntimeError('Unexpected active corpus size')
(ROOT/'active_corpus.json').write_text(json.dumps(corpus,ensure_ascii=False,indent=2),encoding='utf-8')
ids={str(x['chunk']):str(x['claim']) for x in corpus}; allowed=list(ids)
cj=http.cookiejar.CookieJar(); opener=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))
def request(url,data=None,headers={}):
 req=urllib.request.Request(url,data=None if data is None else json.dumps(data,ensure_ascii=False).encode('utf-8'),headers={'Content-Type':'application/json; charset=utf-8',**headers})
 with opener.open(req,timeout=90) as r:return json.load(r)
request('http://127.0.0.1:18080/api/v1/session'); token=next(c.value for c in cj if c.name=='XSRF-TOKEN')
def keyword(q):
 qsql=quoted(q); base=" FROM source_chunk s JOIN source_document d ON d.id=s.source_document_id WHERE s.review_status='APPROVED' AND d.status='ACTIVE' AND "
 exact=sql("SELECT s.id::text"+base+"s.keyword_search_vector @@ websearch_to_tsquery('simple',"+qsql+") ORDER BY ts_rank_cd(s.keyword_search_vector,websearch_to_tsquery('simple',"+qsql+")) DESC,s.id LIMIT 100")
 found=exact.splitlines() if exact else []
 if not found:
  stop={'얼마','얼마인가요','얼마입니까','얼마예요','어떻게'}
  terms=[]
  for word in re.findall(r'[^\W_]+',q,flags=re.UNICODE):
   if word in stop:continue
   word=re.sub(r'(에서도|에게도|으로는|에는|에도|에서|으로|에게|이나|이나요|은|는|이|가|을|를|의|에|도)$','',word)
   if len(word)>=2 and word not in terms:terms.append(word)
  if len(terms)>=2:
   ts=quoted(' & '.join(t+':*' for t in terms)); x=sql("SELECT s.id::text"+base+"s.keyword_search_vector @@ to_tsquery('simple',"+ts+") ORDER BY ts_rank_cd(s.keyword_search_vector,to_tsquery('simple',"+ts+")) DESC,s.id LIMIT 100");found=x.splitlines() if x else []
 ctx=sql("SELECT s.id::text"+base+"to_tsvector('simple',coalesce(d.title,'') || ' ' || coalesce(s.parent_section,'') || ' ' || s.body) @@ websearch_to_tsquery('simple',"+qsql+") ORDER BY s.id LIMIT 100")
 for x in ctx.splitlines():
  if x not in found:found.append(x)
 return [ids[x] for x in found[:100] if x in ids][:3]
rows=list(csv.DictReader((ROOT/'questions.csv').open(encoding='utf-8-sig')))
out=ROOT/'retrieval_raw.csv'
with out.open('w',encoding='utf-8-sig',newline='') as f:
 w=csv.DictWriter(f,fieldnames=['id','method','status','latency_ms','rank1','rank2','rank3','gold_found_at3','error']);w.writeheader();f.flush()
 for row in rows:
  for method in ('FTS','KURE','HYBRID_PRODUCT'):
   start=time.perf_counter();got=[];status='OK';error=''
   try:
    if method=='FTS':got=keyword(row['question'])
    elif method=='KURE':
     r=request('http://127.0.0.1:18092/v1/generations/'+GEN+'/query',{'generationId':GEN,'query':row['question'],'topK':100,'subset':allowed});got=[ids[x['sourceChunkId']] for x in r['results'] if x['sourceChunkId'] in ids][:3]
    else:
     r=request('http://127.0.0.1:18080/api/v1/information-evidence-snapshots',{'question':row['question']},{'X-XSRF-TOKEN':token});status=r.get('status','UNKNOWN');got=[x['claimId'] for x in r.get('evidence',[])][:3]
   except Exception as e:status='ERROR';error=type(e).__name__
   ms=round((time.perf_counter()-start)*1000); got=(got+['','',''])[:3]
   w.writerow({'id':row['id'],'method':method,'status':status,'latency_ms':ms,'rank1':got[0],'rank2':got[1],'rank3':got[2],'gold_found_at3':int(bool(row['gold_claim_ids']) and row['gold_claim_ids'] in got),'error':error});f.flush()
   print(row['id'],method,status,ms,flush=True)
  if sql("SELECT retrieval_generation_id::text FROM active_retrieval_generation WHERE singleton_key=true")!=GEN: raise RuntimeError('Generation changed during evaluation; stopped')
