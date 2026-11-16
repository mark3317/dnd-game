"""Explicit bounded screening of the authenticated catalog; never changes live configuration."""
import argparse,concurrent.futures,copy,json,os,runpy,threading,time,urllib.request,urllib.error
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
def main():
 p=argparse.ArgumentParser();p.add_argument('--report',required=True);p.add_argument('--models',default='all');p.add_argument('--workers',type=int,choices=[1,2],default=2);p.add_argument('--rounds',type=int,choices=[1,2,3],default=2);p.add_argument('--adaptive',action='store_true');p.add_argument('--cache-all',action='store_true');a=p.parse_args()
 env=runpy.run_path(str(ROOT/'scripts/start-ai-server.py'))['server_environment'](os.environ,ROOT/'server/claude.local.json')
 assert env['DND_CLAUDE_BASE_URL'].rstrip('/')=='https://ai.starimg.ru'
 catalog=json.loads((ROOT/'reports/local-ai-evaluation/models-key-20261116.json').read_text())['data']
 models=[x['id'] for x in catalog if 'text' in x.get('output_modalities',[])]
 if a.models!='all':
  selected=a.models.split(',');assert all(x in models for x in selected);models=selected
 assert len(models)*a.rounds<=98
 fixture=json.loads((ROOT/'reports/local-ai-evaluation/cache-game-fixture.json').read_text())
 def txt(v):return v if isinstance(v,str) else ''.join(x.get('text','') for x in v)
 system=txt(fixture['messages'][0]['content']);user=txt(fixture['messages'][1]['content'])
 lock=threading.Lock();counter=0
 with Path(a.report).open('x') as out:
  def emit(value):
   with lock:out.write(json.dumps(value,ensure_ascii=False)+'\n');out.flush()
  def model_run(model):
   nonlocal counter
   native=model.startswith('claude-')
   for round in range(a.rounds):
    if native:
     body={'model':model,'max_tokens':1200,'stream':True,'thinking':{'type':'disabled'},'system':system,'messages':[{'role':'user','content':[{'type':'text','text':user}]}], 'output_config':{'format':{'type':'json_schema','schema':fixture['response_format']['json_schema']['schema']}}}
     if a.adaptive:body['thinking']={'type':'adaptive'};body['output_config']['effort']='low'
     if round or a.cache_all:body['messages'][0]['content'][0]['cache_control']={'type':'ephemeral'}
     endpoint='/v1/messages';cache_mode='anthropic_explicit' if round or a.cache_all else 'baseline'
    else:
     body=copy.deepcopy(fixture);body['model']=model;body['stream']=True;body['stream_options']={'include_usage':True}
     body.pop('reasoning_effort',None)
     if model.startswith('gpt-'):body['reasoning_effort']='none' if model=='gpt-6-luna' else 'low'
     if (round or a.cache_all) and model.startswith('gpt-'):body.update(prompt_cache_key='dnd-model-benchmark-'+model,prompt_cache_retention='24h')
     endpoint='/v1/chat/completions';cache_mode=('openai_retention' if model.startswith('gpt-') else 'automatic_repeat') if round or a.cache_all else 'baseline'
    with lock:counter+=1;attempt=counter
    emit({'event':'attempt_started','attempt':attempt,'model':model,'round':round,'cache_mode':cache_mode,'endpoint':endpoint,'reasoning':body.get('reasoning_effort',body.get('thinking','provider_default'))})
    started=time.monotonic();first=None;output='';usage={};finish=None;result={'model':model,'round':round,'cache_mode':cache_mode,'attempt':attempt}
    req=urllib.request.Request(env['DND_CLAUDE_BASE_URL'].rstrip('/')+endpoint,data=json.dumps(body).encode(),headers={'Authorization':'Bearer '+env['ANTHROPIC_API_KEY'],'Content-Type':'application/json','anthropic-version':'2023-06-01'})
    try:
     with urllib.request.urlopen(req,timeout=30) as response:
      for raw in response:
       if time.monotonic()-started>35:raise TimeoutError('deadline')
       line=raw.decode().strip()
       if not line.startswith('data:'):continue
       data=line[5:].strip()
       if data=='[DONE]':break
       event=json.loads(data)
       if 'error' in event:raise ValueError(json.dumps(event['error']))
       if native:
        if event.get('type')=='message_start':usage.update(event.get('message',{}).get('usage',{}))
        usage.update(event.get('usage',{}));delta=event.get('delta',{});chunk=delta.get('text','');finish=delta.get('stop_reason',finish)
       else:
        usage.update(event.get('usage') or {});choices=event.get('choices',[]);choice=choices[0] if choices else {};chunk=choice.get('delta',{}).get('content') or '';finish=choice.get('finish_reason') or finish
       if chunk and first is None:first=round_ms(started)
       output+=chunk
     result.update(first_text_ms=first,elapsed_ms=round_ms(started),usage=usage,finish=finish,output=output)
    except urllib.error.HTTPError as error:
     result.update(elapsed_ms=round_ms(started),http_status=error.code,error=error.read().decode(errors='replace').replace(env['ANTHROPIC_API_KEY'],'[redacted]')[:1000])
    except Exception as error:
     result.update(elapsed_ms=round_ms(started),error=type(error).__name__,first_text_ms=first)
    emit(result);print(json.dumps({k:v for k,v in result.items() if k not in ['output','usage']},ensure_ascii=False),flush=True)
    if 'error' in result:break
  with concurrent.futures.ThreadPoolExecutor(max_workers=a.workers) as pool:list(pool.map(model_run,models))
def round_ms(start):return int((time.monotonic()-start)*1000)
if __name__=='__main__':main()
