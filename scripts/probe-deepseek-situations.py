"""17 explicitly requested sequential probes. No retries, no live-game mutations."""
import argparse,copy,hashlib,json,os,runpy,time,urllib.request,urllib.error
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
def main():
 p=argparse.ArgumentParser();p.add_argument('--fixtures',required=True);p.add_argument('--report',required=True);a=p.parse_args()
 fixtures=json.loads(Path(a.fixtures).read_text());assert len(fixtures)==9
 env=runpy.run_path(str(ROOT/'scripts/start-ai-server.py'))['server_environment'](os.environ,ROOT/'server/claude.local.json')
 assert env['DND_CLAUDE_BASE_URL'].rstrip('/')=='https://ai.starimg.ru'
 with Path(a.report).open('x') as out:
  def emit(row):out.write(json.dumps(row,ensure_ascii=False)+'\n');out.flush()
  attempt=0
  for fixture in fixtures:
   for repeat in range(1 if fixture['outcome'] else 2):
    attempt+=1;assert attempt<=17
    body=copy.deepcopy(fixture['body']);body.update(model='deepseek-v4-pro-0813',stream=True,stream_options={'include_usage':True});body.pop('reasoning_effort',None)
    payload=json.dumps(body,ensure_ascii=False).encode()
    emit({'event':'attempt_started','attempt':attempt,'case':fixture['case'],'repeat':repeat,'sha256':hashlib.sha256(payload).hexdigest(),'cache_mode':'provider_automatic','model':body['model']})
    row={'attempt':attempt,'case':fixture['case'],'repeat':repeat};start=time.monotonic();first=None;output='';usage={};finish=None
    req=urllib.request.Request(env['DND_CLAUDE_BASE_URL'].rstrip('/')+'/v1/chat/completions',data=payload,headers={'Authorization':'Bearer '+env['ANTHROPIC_API_KEY'],'Content-Type':'application/json'})
    try:
     with urllib.request.urlopen(req,timeout=30) as response:
      for raw in response:
       if time.monotonic()-start>35:raise TimeoutError('deadline')
       line=raw.decode().strip()
       if not line.startswith('data:'):continue
       data=line[5:].strip()
       if data=='[DONE]':break
       event=json.loads(data)
       if 'error'in event:raise ValueError('provider_stream_error')
       usage.update(event.get('usage') or {});choices=event.get('choices',[]);choice=choices[0] if choices else {};chunk=choice.get('delta',{}).get('content') or '';finish=choice.get('finish_reason') or finish
       if chunk and first is None:first=int((time.monotonic()-start)*1000)
       output+=chunk
     row.update(output=output,usage=usage,finish=finish)
    except urllib.error.HTTPError as e:row.update(http_status=e.code,error=e.read().decode(errors='replace').replace(env['ANTHROPIC_API_KEY'],'[redacted]')[:1000])
    except Exception as e:row['error']=type(e).__name__
    row.update(elapsed_ms=int((time.monotonic()-start)*1000),first_text_ms=first)
    emit(row);print(json.dumps({k:v for k,v in row.items() if k not in ['output','usage']},ensure_ascii=False),flush=True)
    if row.get('http_status') in [401,403]:break
   if row.get('http_status') in [401,403]:break
if __name__=='__main__':main()
