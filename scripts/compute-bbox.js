const fs = require('fs')
const path = require('path')

function tokenize(d){
  const tokens = []
  let num = ''
  for(let i=0;i<d.length;i++){
    const ch = d[i]
    if((ch>='0'&&ch<='9')||ch==='.'||ch==='-'||ch==='+'){ num+=ch }
    else if(ch===','||ch===' '||ch==='\t'||ch==='\n'||ch==='\r'){
      if(num!==''){ tokens.push(num); num=''}
    } else {
      if(num!==''){ tokens.push(num); num=''}
      tokens.push(ch)
    }
  }
  if(num!=='') tokens.push(num)
  return tokens
}

function parsePath(d){
  const tokens = tokenize(d)
  let i=0
  const commands = []
  while(i<tokens.length){
    let t = tokens[i]
    if(/^[a-zA-Z]$/.test(t)){
      const cmd = t
      i++
      const params = []
      const paramCounts = {
        'M':2,'m':2,'L':2,'l':2,'H':1,'h':1,'V':1,'v':1,'C':6,'c':6,'S':4,'s':4,'Q':4,'q':4,'T':2,'t':2,'A':7,'a':7,'Z':0,'z':0
      }
      const need = paramCounts[cmd]
      if(need===undefined) throw new Error('Unsupported command '+cmd)
      if(need===0){ commands.push({cmd, params:[]}); continue }
      // consume params until next letter or end
      while(i<tokens.length && !/^[a-zA-Z]$/.test(tokens[i])){
        params.push(parseFloat(tokens[i])); i++
      }
      // split into chunks of need
      for(let j=0;j<params.length;j+=need){
        const slice = params.slice(j,j+need)
        if(slice.length===need) commands.push({cmd, params: slice})
      }
    } else {
      // stray numbers: assume previous command repeats
      // attach to last command type
      if(commands.length===0) throw new Error('Number before command')
      const last = commands[commands.length-1].cmd
      const paramCounts = { 'M':2,'m':2,'L':2,'l':2,'H':1,'h':1,'V':1,'v':1,'C':6,'c':6,'S':4,'s':4,'Q':4,'q':4,'T':2,'t':2,'A':7,'a':7 }
      const need = paramCounts[last]
      if(!need) throw new Error('Unexpected token sequence')
      const params = []
      while(i<tokens.length && !/^[a-zA-Z]$/.test(tokens[i]) && params.length<need){ params.push(parseFloat(tokens[i])); i++ }
      if(params.length===need) commands.push({cmd: last, params})
    }
  }
  return commands
}

function sampleCubic(p0,p1,p2,p3,t){
  const x = Math.pow(1-t,3)*p0.x + 3*Math.pow(1-t,2)*t*p1.x + 3*(1-t)*Math.pow(t,2)*p2.x + Math.pow(t,3)*p3.x
  const y = Math.pow(1-t,3)*p0.y + 3*Math.pow(1-t,2)*t*p1.y + 3*(1-t)*Math.pow(t,2)*p2.y + Math.pow(t,3)*p3.y
  return {x,y}
}

function sampleQuadratic(p0,p1,p2,t){
  const x = Math.pow(1-t,2)*p0.x + 2*(1-t)*t*p1.x + Math.pow(t,2)*p2.x
  const y = Math.pow(1-t,2)*p0.y + 2*(1-t)*t*p1.y + Math.pow(t,2)*p2.y
  return {x,y}
}

function computeBBox(commands){
  let cur = {x:0,y:0}
  let start = {x:0,y:0}
  let prevControl = null
  let minX=Infinity, minY=Infinity, maxX=-Infinity, maxY=-Infinity
  function addPoint(p){ if(p.x<minX) minX=p.x; if(p.x>maxX) maxX=p.x; if(p.y<minY) minY=p.y; if(p.y>maxY) maxY=p.y }

  for(const c of commands){
    const cmd = c.cmd; const p = c.params
    if(cmd==='M'){ cur={x:p[0], y:p[1]}; start={...cur}; addPoint(cur); prevControl=null }
    else if(cmd==='m'){ cur={x:cur.x + p[0], y: cur.y + p[1]}; start={...cur}; addPoint(cur); prevControl=null }
    else if(cmd==='L'){ cur={x:p[0], y:p[1]}; addPoint(cur); prevControl=null }
    else if(cmd==='l'){ cur={x:cur.x + p[0], y:cur.y + p[1]}; addPoint(cur); prevControl=null }
    else if(cmd==='H'){ cur={x:p[0], y:cur.y}; addPoint(cur); prevControl=null }
    else if(cmd==='h'){ cur={x:cur.x + p[0], y:cur.y}; addPoint(cur); prevControl=null }
    else if(cmd==='V'){ cur={x:cur.x, y:p[0]}; addPoint(cur); prevControl=null }
    else if(cmd==='v'){ cur={x:cur.x, y:cur.y + p[0]}; addPoint(cur); prevControl=null }
    else if(cmd==='C'){
      const p1={x:p[0],y:p[1]}, p2={x:p[2],y:p[3]}, p3={x:p[4],y:p[5]}, p0={...cur}
      // sample cubic
      const steps=200
      for(let t=0;t<=1;t+=1/steps){ const pt=sampleCubic(p0,p1,p2,p3,t); addPoint(pt) }
      cur={x:p3.x,y:p3.y}; prevControl=p2
    }
    else if(cmd==='c'){
      const p1={x:cur.x + p[0], y:cur.y + p[1]}
      const p2={x:cur.x + p[2], y:cur.y + p[3]}
      const p3={x:cur.x + p[4], y:cur.y + p[5]}
      const p0={...cur}
      const steps=200
      for(let t=0;t<=1;t+=1/steps){ const pt=sampleCubic(p0,p1,p2,p3,t); addPoint(pt) }
      cur={x:p3.x,y:p3.y}; prevControl=p2
    }
    else if(cmd==='Q'){
      const p1={x:p[0],y:p[1]}, p2={x:p[2],y:p[3]}, p0={...cur}
      const steps=200
      for(let t=0;t<=1;t+=1/steps){ const pt=sampleQuadratic(p0,p1,p2,t); addPoint(pt) }
      cur={x:p2.x,y:p2.y}; prevControl=p1
    }
    else if(cmd==='q'){
      const p1={x:cur.x + p[0], y:cur.y + p[1]}, p2={x:cur.x + p[2], y:cur.y + p[3]}, p0={...cur}
      const steps=200
      for(let t=0;t<=1;t+=1/steps){ const pt=sampleQuadratic(p0,p1,p2,t); addPoint(pt) }
      cur={x:p2.x,y:p2.y}; prevControl=p1
    }
    else if(cmd==='Z' || cmd==='z'){
      cur={...start}; addPoint(cur); prevControl=null }
    else if(cmd==='S' || cmd==='s' || cmd==='T' || cmd==='t' || cmd==='A' || cmd==='a'){
      // fallback: approximate by adding current point and next endpoint
      // For S/s and T/t we need more, but path likely doesn't rely on them here
      if(cmd==='S' || cmd==='s'){
        // 4 params
        const pts = cmd==='S' ? [{x:p[2],y:p[3]}] : [{x:cur.x+p[2], y:cur.y+p[3]}]
        for(const pt of pts) addPoint(pt)
      } else if(cmd==='T' || cmd==='t'){
        const pt = cmd==='T' ? {x:p[0], y:p[1]} : {x:cur.x + p[0], y:cur.y + p[1]}
        addPoint(pt); cur=pt
      } else if(cmd==='A' || cmd==='a'){
        // arc: endpoint last two params
        const ex = cmd==='A' ? p[5] : cur.x + p[5]
        const ey = cmd==='A' ? p[6] : cur.y + p[6]
        addPoint({x:ex,y:ey}); cur={x:ex,y:ey}
      }
    }
    else {
      // unknown command: ignore
    }
  }
  return {minX, minY, maxX, maxY}
}

function main(){
  const file = path.join(__dirname, '..', 'android', 'app', 'src', 'main', 'res', 'drawable', 'splash_simple.xml')
  const xml = fs.readFileSync(file,'utf8')
  const m = xml.match(/android:pathData\s*=\s*"([^"]+)"/m)
  if(!m){ console.error('No pathData found'); process.exit(1) }
  const d = m[1]
  const commands = parsePath(d)
  const bbox = computeBBox(commands)
  console.log('BBOX', bbox)
  const viewportW = 24; const viewportH = 24
  // desired padding (units) - keep 10% padding of viewport
  const padding = 2
  const pathW = bbox.maxX - bbox.minX
  const pathH = bbox.maxY - bbox.minY
  const scale = Math.min((viewportW - padding*2)/pathW, (viewportH - padding*2)/pathH)
  const tx = (viewportW - pathW*scale)/2 - bbox.minX*scale
  const ty = (viewportH - pathH*scale)/2 - bbox.minY*scale
  console.log('SCALE', scale.toFixed(6), 'TX', tx.toFixed(6), 'TY', ty.toFixed(6))
}

main()
