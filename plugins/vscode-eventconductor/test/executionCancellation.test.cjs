const assert = require('node:assert/strict');
const test = require('node:test');
const {ExecutionCancellation} = require('../out/executionCancellation');
const {EngineError} = require('../out/engineClient');
const graph = (id,status='RUNNING',extra={}) => ({process:{id,status,...extra},steps:[]});
function control(client,timeoutMs=200) {
  const states=[];const op=new ExecutionCancellation(client,state=>states.push({...state}),{pollMs:1,timeoutMs});
  return {op,states,signal:new AbortController().signal};
}
test('Stop posts a server command and confirms only after observing CANCELLED; duplicate clicks share it',async()=>{
  let posted=0,read=0;
  const h=control({graph:async id=>graph(id,++read>=3?'CANCELLED':'RUNNING'),cancel:async()=>{posted++;}});
  const a=h.op.stop('root',true,h.signal), b=h.op.stop('root',true,h.signal);
  assert.equal(a,b);assert.equal(h.op.busy,true);assert.equal(await a,'root');
  assert.equal(posted,1);assert.equal(h.op.state.phase,'confirmed');assert.equal(h.op.busy,false);
  assert.equal(h.states.find(s=>s.phase==='accepted').pending,true);
  assert.equal(h.states.at(-1).pending,false);assert.equal(h.states.filter(s=>s.phase==='confirmed').length>=1,true);
});
test('a 202 that never becomes CANCELLED remains unconfirmed and is not resent automatically',async()=>{
  let posted=0;
  const h=control({graph:async id=>graph(id),cancel:async()=>{posted++;}},12);
  await h.op.stop('root',true,h.signal);assert.equal(posted,1);assert.equal(h.op.state.phase,'unknown');assert.match(h.op.state.message,/may still be running/);
});
test('continuations before a stop and a 409 race target the current linked execution',async()=>{
  let oldReads=0;const posted=[];
  const h=control({graph:async id=>id==='old'?graph(id,++oldReads===1?'RUNNING':'COMPLETED',oldReads>1?{continuedAs:'next'}:{}):graph(id,posted.includes(id)?'CANCELLED':'RUNNING'),
    cancel:async id=>{posted.push(id);if(id==='old')throw new EngineError('Already completed',409);}});
  assert.equal(await h.op.stop('old',true,h.signal),'next');assert.deepEqual(posted,['old','next']);assert.equal(h.op.state.phase,'confirmed');
});
test('a continuation committed after acknowledgement is followed and cancelled too',async()=>{
  const posted=[];
  const h=control({graph:async id=>id==='old'?graph(id,posted.includes(id)?'COMPLETED':'RUNNING',posted.includes(id)?{continuedAs:'next'}:{}):graph(id,posted.includes(id)?'CANCELLED':'RUNNING'),cancel:async id=>posted.push(id)});
  assert.equal(await h.op.stop('old',true,h.signal),'next');assert.deepEqual(posted,['old','next']);assert.equal(h.op.state.phase,'confirmed');
});
test('historical completed executions and circular continuation links do not cancel unrelated processes',async()=>{
  let posted=0;const client={graph:async id=>graph(id,'COMPLETED',{continuedAs:'next'}),cancel:async()=>{posted++;}};
  const history=control(client);await history.op.stop('old',false,history.signal);assert.equal(history.op.state.phase,'finished');assert.equal(posted,0);
  const cycle=control(client);await cycle.op.stop('next',true,cycle.signal);assert.equal(posted,0);assert.notEqual(cycle.op.state.phase,'confirmed');
});
test('permission refusals and missing executions never become a successful stop',async()=>{
  for(const status of [401,403,404]) {
    const h=control({graph:async id=>graph(id),cancel:async()=>{throw new EngineError('Access refused',status);}});
    await h.op.stop('root',true,h.signal);assert.equal(h.op.state.phase,'failed');assert.match(h.op.state.message,/not confirmed/);
  }
});
test('a lost cancellation response is verified without sending a second command',async()=>{
  let posted=0;
  const h=control({graph:async id=>graph(id,posted?'CANCELLED':'RUNNING'),cancel:async()=>{posted++;throw new EngineError('Disconnected');}});
  await h.op.stop('root',true,h.signal);assert.equal(posted,1);assert.equal(h.op.state.phase,'confirmed');assert.ok(h.states.some(s=>s.phase==='unknown'));
});
test('agent review is reported as review, never as a confirmed cancellation',async()=>{
  let posted=false;
  const h=control({graph:async id=>posted?{...graph(id,'PAUSED'),steps:[{agent:{underReview:true}}]}:graph(id),cancel:async()=>{posted=true;}});
  await h.op.stop('root',true,h.signal);assert.equal(h.op.state.phase,'review');assert.match(h.op.state.message,/not yet confirmed/);
});
test('aborting verification never claims the process stopped',async()=>{
  const abort=new AbortController();
  const h=control({graph:async id=>graph(id),cancel:async()=>abort.abort()});
  await h.op.stop('root',true,abort.signal);assert.equal(h.op.state.phase,'unknown');assert.equal(h.op.busy,false);
});


test('an accepted command that leaves the process running is retried idempotently and verified',async()=>{
  let posted=0;const states=[];
  const op=new ExecutionCancellation({graph:async id=>graph(id,posted>=2?'CANCELLED':'RUNNING'),cancel:async()=>{posted++;}},state=>states.push(state),{pollMs:1,timeoutMs:60,retryMs:2});
  await op.stop('root',true,new AbortController().signal);
  assert.equal(posted,2);assert.equal(op.state.phase,'confirmed');assert.equal(states.filter(s=>s.phase==='accepted').length,2);
});
test('a persistently active process gets at most three commands and is never reported as stopped',async()=>{
  let posted=0;
  const op=new ExecutionCancellation({graph:async id=>graph(id),cancel:async()=>{posted++;}},()=>{},{pollMs:1,timeoutMs:14,retryMs:2});
  await op.stop('root',true,new AbortController().signal);assert.equal(posted,3);assert.equal(op.state.phase,'unknown');
});
