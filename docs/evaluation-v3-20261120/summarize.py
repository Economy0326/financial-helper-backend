import csv,json,math,statistics
from pathlib import Path
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
root=Path(__file__).resolve().parent
questions={r['id']:r for r in csv.DictReader((root/'questions.csv').open(encoding='utf-8-sig'))}
rows=list(csv.DictReader((root/'retrieval_raw.csv').open(encoding='utf-8-sig')))
def percentile(v,p):
 v=sorted(v); x=(len(v)-1)*p; a=int(x); b=min(a+1,len(v)-1); return v[a]+(v[b]-v[a])*(x-a)
out=[]
for method in ('FTS','KURE','HYBRID_PRODUCT'):
 subset=[x for x in rows if x['method']==method];good=[x for x in subset if x['status']!='ERROR'];positive=[x for x in good if questions[x['id']]['gold_claim_ids']];negative=[x for x in good if not questions[x['id']]['gold_claim_ids']];times=[int(x['latency_ms']) for x in good]
 recalls=[int(x['gold_found_at3']) for x in positive]
 reciprocal=[(1/math.log2(2+[x['rank1'],x['rank2'],x['rank3']].index(questions[x['id']]['gold_claim_ids']))) if int(x['gold_found_at3']) else 0 for x in positive]
 out.append({'method':method,'cases':len(subset),'errors':len(subset)-len(good),'positive_n':len(positive),'known_gold_hit_at_3':round(sum(recalls)/len(recalls),4) if recalls else None,'single_label_p_at_3_proxy':round(sum(recalls)/(3*len(recalls)),4) if recalls else None,'single_label_ndcg_at_3_proxy':round(sum(reciprocal)/len(reciprocal),4) if reciprocal else None,'no_gold_candidates':sum(bool(x['rank1']) for x in negative),'negative_n':len(negative),'p50_ms':round(statistics.median(times)) if times else None,'p95_ms':round(percentile(times,.95)) if times else None})
with (root/'retrieval_summary.csv').open('w',encoding='utf-8-sig',newline='') as f:
 w=csv.DictWriter(f,fieldnames=out[0].keys());w.writeheader();w.writerows(out)
fig,ax=plt.subplots(1,2,figsize=(11,4));labels=[x['method'] for x in out];ax[0].bar(labels,[x['known_gold_hit_at_3'] or 0 for x in out],color='#169c99');ax[0].set_ylim(0,1);ax[0].set_title('Known target hit@3 (partial labels)');ax[1].bar(labels,[x['p50_ms']/1000 for x in out],color='#169c99');ax[1].set_title('p50 per call (seconds)');fig.tight_layout();fig.savefig(root/'retrieval_summary.png',dpi=140)
print(json.dumps(out,ensure_ascii=False,indent=2))
