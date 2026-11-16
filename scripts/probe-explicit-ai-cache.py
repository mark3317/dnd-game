"""Bounded opt-in cache probe using an offline-exported game request; no retries."""
import argparse,copy,json,os,runpy,time,urllib.request,urllib.error
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
def main():
    ap=argparse.ArgumentParser()
    ap.add_argument('--fixture',required=True)
    ap.add_argument('--report',required=True)
    args=ap.parse_args()
    env=runpy.run_path(str(ROOT/'scripts/start-ai-server.py'))['server_environment'](os.environ,ROOT/'server/claude.local.json')
    assert env['DND_CLAUDE_MODEL']=='gpt-6-luna' and env['DND_CLAUDE_BASE_URL'].rstrip('/')=='https://ai.starimg.ru'
    fixture=json.loads(Path(args.fixture).read_text())
    def text(content):
        return content if isinstance(content,str) else ''.join(p.get('text','') for p in content)
    system=text(fixture['messages'][0]['content'])
    context=json.loads(text(fixture['messages'][1]['content']))
    intent=context.pop('player_intent')
    prefix=system+'\n'+json.dumps(context,ensure_ascii=False,separators=(',',':'))
    schema=fixture['response_format']['json_schema']
    base={'model':env['DND_CLAUDE_MODEL'],'reasoning':{'effort':'none'},'max_output_tokens':1200,'store':False,
          'text':{'format':{'type':'json_schema',**schema}},'prompt_cache_key':'dnd-cache-probe-v1',
          'prompt_cache_options':{'mode':'explicit','ttl':'30m'},
          'input':[{'role':'developer','content':[{'type':'input_text','text':prefix}]},{'role':'user','content':intent}]}
    with Path(args.report).open('x') as out:
        def request(body,endpoint,label):
            out.write(json.dumps({'event':'attempt_started','label':label,'endpoint':endpoint})+'\n');out.flush()
            started=time.monotonic()
            req=urllib.request.Request(env['DND_CLAUDE_BASE_URL'].rstrip('/')+endpoint,data=json.dumps(body).encode(),headers={'Authorization':'Bearer '+env['ANTHROPIC_API_KEY'],'Content-Type':'application/json'})
            try:
                with urllib.request.urlopen(req,timeout=30) as r: data=json.load(r)
                result={'label':label,'elapsed_ms':round((time.monotonic()-started)*1000),'usage':data.get('usage'),'output':data.get('output',data.get('choices')),'status':data.get('status'),'cache_options':data.get('prompt_cache_options')}
            except urllib.error.HTTPError as e:
                raw=e.read().decode(errors='replace').replace(env['ANTHROPIC_API_KEY'],'[redacted]')
                result={'label':label,'elapsed_ms':round((time.monotonic()-started)*1000),'http_status':e.code,'error':raw[:1200]}
            except Exception as e:
                result={'label':label,'elapsed_ms':round((time.monotonic()-started)*1000),'error':type(e).__name__}
            out.write(json.dumps(result,ensure_ascii=False)+'\n');out.flush()
            print(json.dumps({k:v for k,v in result.items() if k!='output'},ensure_ascii=False),flush=True)
            return result
        for label,enabled in [('explicit-warmup',True),('no-breakpoint-1',False),('explicit-repeat',True),('no-breakpoint-2',False)]:
            body=copy.deepcopy(base)
            if enabled:body['input'][0]['content'][0]['prompt_cache_breakpoint']={'mode':'explicit'}
            result=request(body,'/v1/responses',label)
            if 'error' in result:
                # A missing endpoint or unsupported schema can be probed via the existing Chat API.
                if label=='explicit-warmup' and result.get('http_status') in [400,404,422]:
                    chat=copy.deepcopy(fixture)
                    chat.update(prompt_cache_key='dnd-cache-probe-chat-v1',prompt_cache_retention='24h')
                    for i in range(2):
                        if 'error' in request(chat,'/v1/chat/completions',f'chat-retention-{i+1}'):break
                break
if __name__=='__main__':main()
