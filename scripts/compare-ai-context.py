"""Explicit 16-request A/B probe; uses the standard secret loader, no retries.
Existing report files cause the run to stop. Build :server:installDist first.
"""
from pathlib import Path
import subprocess,json
root=Path(__file__).resolve().parents[1]
failures=0
for repeat in range(2):
 for i,case in enumerate(['GM02','GM04','GM11','GM14']):
  modes=['full','compact'] if (repeat+i)%2==0 else ['compact','full']
  for mode in modes:
   name=f'context-ab-r{repeat+1}-{case}-{mode}'
   report=f'reports/local-ai-evaluation/{name}.jsonl'
   cmd=['python3','scripts/start-ai-server.py','--','--evaluate-ai','--case-set','demo-v1','--cases',case,'--max-requests','1','--context-mode',mode,'--report',report,'--review',f'reports/local-ai-evaluation/{name}-review.jsonl','--revision','context-ab-v1']
   result=subprocess.run(cmd,cwd=root,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
   if result.returncode: raise SystemExit('Runner failed; stopped')
   rows=[json.loads(s) for s in (root/report).read_text().splitlines()]
   item=next(x for x in rows if x.get('status')=='attempted')
   print(name,json.dumps(item),flush=True)
   failure=item.get('failure')
   failures=failures+1 if failure in ['transport_timeout','connection_error','dns_error','tls_error','request_timeout','api_client_error'] else 0
   if failures>=2 or item.get('http_status') in ['401','403']: raise SystemExit('Circuit breaker stopped series')
