/* Render-only compatibility for TeX delimiters; source text is never rewritten. */
(() => {
  'use strict';
  function normalize(source) {
    let text='',legacy=false,index=0,fence=null;
    const escaped=at=>{let count=0;while(at>0&&source[--at]==='\\')count++;return count%2===1;};
    const endOfLine=at=>{const end=source.indexOf('\n',at);return end<0?source.length:end+1;};
    function closing(marker,from) {
      let at=source.indexOf(marker,from);
      while(at>=0&&escaped(at))at=source.indexOf(marker,at+marker.length);
      return at;
    }
    while(index<source.length) {
      // Leave fenced/indented code (including quoted fences) untouched.
      if(index===0||source[index-1]==='\n') {
        const end=endOfLine(index),line=source.slice(index,end);
        const match=line.match(/^(?: {0,3}> ?)* {0,3}(`{3,}|~{3,})(.*)/);
        if(fence) {
          text+=line;index=end;
          if(match&&match[1][0]===fence.char&&match[1].length>=fence.size&&/^\s*$/.test(match[2]))fence=null;
          continue;
        }
        if(match&&(match[1][0]!=='`'||!match[2].includes('`'))) {
          fence={char:match[1][0],size:match[1].length};text+=line;index=end;continue;
        }
        if(/^(?: {4}|\t)/.test(line)){text+=line;index=end;continue;}
      }
      if(source[index]==='`') {
        const marker=source.slice(index).match(/^`+/)[0];let end=source.indexOf(marker,index+marker.length);
        while(end>=0&&(source[end-1]==='`'||source[end+marker.length]==='`'))end=source.indexOf(marker,end+marker.length);
        const next=end<0?index+marker.length:end+marker.length;text+=source.slice(index,next);index=next;continue;
      }
      if(source[index]==='<') {
        const rest=source.slice(index);
        const raw=rest.match(/^<(pre|code|script|style|textarea)\b[^>]*>[\s\S]*?<\/\1\s*>/i)
          ||rest.match(/^<!--[\s\S]*?-->/)
          ||rest.match(/^<\/?[A-Za-z][A-Za-z0-9-]*(?:\s+(?:"[^"]*"|'[^']*'|[^'">])*)?\s*\/?>/);
        if(raw){text+=raw[0];index+=raw[0].length;continue;}
      }
      if(source[index]==='$'&&!escaped(index)) {
        const marker=source[index+1]==='$'?'$$':'$',end=closing(marker,index+marker.length);
        if(end>=0&&(marker==='$$'||!source.slice(index,end).includes('\n'))) {
          const next=end+marker.length;text+=source.slice(index,next);index=next;continue;
        }
      }
      if(source[index]==='\\'&&source[index+1]==='\\'){text+='\\\\';index+=2;continue;}
      if(source[index]==='\\'&&(source[index+1]==='['||source[index+1]==='(')&&!escaped(index)) {
        const display=source[index+1]==='[',end=closing(display?'\\]':'\\)',index+2);
        const content=end<0?'':source.slice(index+2,end);
        if(end>=0&&(display||!/[\r\n]/.test(content))) {
          text+=display?'$$\n'+content.replace(/^\r?\n/,'').replace(/\r?\n$/,'')+'\n$$':'$'+content+'$';
          legacy=true;index=end+2;continue;
        }
      }
      text+=source[index++];
    }
    return {text,legacy};
  }
  globalThis.MoyueMath={normalize};
})();
