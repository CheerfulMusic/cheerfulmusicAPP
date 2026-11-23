/** UV coordinates follow the artwork from top left to bottom right. */
const vertex = `
attribute vec2 corner;
varying vec2 uv;
void main(){
  uv=vec2(corner.x*.5+.5,.5-corner.y*.5);
  gl_Position=vec4(corner,0.,1.);
}`
const fragment = `
precision highp float;
varying vec2 uv;
uniform sampler2D scene;
uniform float clock;
uniform float angle;
void main(){
  vec2 pos=uv;
  // Deform the inclined oval in its own plane so the galaxy keeps its silhouette.
  vec2 offset=(uv-vec2(.757,.245))*vec2(1.78,1.);
  vec2 major=vec2(.936,-.352), minor=vec2(.352,.936);
  vec2 orbit=vec2(dot(offset,major)/.43,dot(offset,minor)/.125);
  float blend=1.-smoothstep(.65,1.20,length(orbit));
  vec2 rotated=mat2(cos(angle),-sin(angle),sin(angle),cos(angle))*orbit;
  vec2 spiral=vec2(.757,.245)+(major*rotated.x*.43+minor*rotated.y*.125)/vec2(1.78,1.);
  vec4 color=mix(texture2D(scene,pos),texture2D(scene,spiral),blend);
  float canopy=1.-smoothstep(.78,1.,length((uv-vec2(.02,.02))/vec2(.29,.46)));
  pos.x+=canopy*.0027*(sin(clock*.95+uv.y*12.)+.36*sin(clock*1.62+uv.x*11.));
  pos.y+=canopy*.0016*sin(clock*.9+uv.x*14.);
  float lake=smoothstep(.75,.83,uv.y)*smoothstep(.13,.31,uv.x);
  pos.y+=lake*(.0017*sin(uv.y*145.+clock*1.4)+.0009*sin(uv.x*28.+clock));
  gl_FragColor=mix(color,texture2D(scene,pos),max(canopy,lake));
}`

export function animateGalaxy(canvas:HTMLCanvasElement, artwork:HTMLImageElement, accelerated:()=>boolean){
  const gl=canvas.getContext('webgl',{alpha:false,antialias:false})
  if(!gl)return
  const program=gl.createProgram()
  if(!program)return
  for(const [type,source] of [[gl.VERTEX_SHADER,vertex],[gl.FRAGMENT_SHADER,fragment]] as const){
    const shader=gl.createShader(type)
    if(!shader){gl.deleteProgram(program);return}
    gl.shaderSource(shader,source);gl.compileShader(shader)
    gl.attachShader(program,shader);gl.deleteShader(shader)
  }
  gl.linkProgram(program)
  if(!gl.getProgramParameter(program,gl.LINK_STATUS)){gl.deleteProgram(program);return}
  gl.useProgram(program)
  const buffer=gl.createBuffer(), texture=gl.createTexture()
  gl.bindBuffer(gl.ARRAY_BUFFER,buffer)
  gl.bufferData(gl.ARRAY_BUFFER,new Float32Array([-1,-1,1,-1,-1,1,1,1]),gl.STATIC_DRAW)
  const corner=gl.getAttribLocation(program,'corner')
  gl.enableVertexAttribArray(corner);gl.vertexAttribPointer(corner,2,gl.FLOAT,false,0,0)
  gl.bindTexture(gl.TEXTURE_2D,texture)
  gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_WRAP_S,gl.CLAMP_TO_EDGE)
  gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_WRAP_T,gl.CLAMP_TO_EDGE)
  gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MIN_FILTER,gl.LINEAR)
  gl.texImage2D(gl.TEXTURE_2D,0,gl.RGB,gl.RGB,gl.UNSIGNED_BYTE,artwork)
  const clock=gl.getUniformLocation(program,'clock'),rotation=gl.getUniformLocation(program,'angle')
  let frame=0,last=0,time=0,angle=0,speed=.07,visible=true
  function draw(now:number){
    frame=requestAnimationFrame(draw)
    if(now-last<32)return
    const dt=last?Math.min((now-last)/1000,.08):0
    last=now
    time+=dt
    const targetSpeed=accelerated() ? .48 : .07
    speed+=(targetSpeed-speed)*Math.min(1,dt*3)
    angle+=dt*speed
    const ratio=Math.min(devicePixelRatio,2)
    const width=Math.max(1,Math.round(canvas.clientWidth*ratio)),height=Math.max(1,Math.round(canvas.clientHeight*ratio))
    if(canvas.width!==width||canvas.height!==height){canvas.width=width;canvas.height=height}
    gl!.viewport(0,0,width,height);gl!.uniform1f(clock,time);gl!.uniform1f(rotation,angle);gl!.drawArrays(gl!.TRIANGLE_STRIP,0,4)
    canvas.style.opacity='1'
  }
  function sync(){
    cancelAnimationFrame(frame);last=0
    if(visible&&!document.hidden)frame=requestAnimationFrame(draw)
  }
  const observer=new IntersectionObserver(([entry])=>{visible=!!entry?.isIntersecting;sync()})
  observer.observe(canvas);document.addEventListener('visibilitychange',sync)
  return ()=>{observer.disconnect();document.removeEventListener('visibilitychange',sync);cancelAnimationFrame(frame);gl.deleteTexture(texture);gl.deleteBuffer(buffer);gl.deleteProgram(program);canvas.style.opacity='0'}
}
