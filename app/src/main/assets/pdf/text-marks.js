/* DOM-only marks: book source and editor content are never modified. */
window.MoyueTextMarks = class {
  constructor(root, selected, missing) {
    this.root=root; this.selected=selected; this.missing=missing; this.items=[]; this.pending=null;
    this.bar=document.createElement('div'); this.bar.dataset.moyueUi='true';
    Object.assign(this.bar.style,{position:'fixed',bottom:'84px',left:'50%',transform:'translateX(-50%)',zIndex:'100000',display:'none',background:'#fff',color:'#222',border:'1px solid #ccc',borderRadius:'10px',padding:'8px',boxShadow:'0 2px 12px #0003'});
    for (const [type,name] of [['HIGHLIGHT','高亮'],['NOTE','笔记']]) {
      const button=document.createElement('button'); button.textContent=name; button.type='button';
      Object.assign(button.style,{fontSize:'16px',padding:'8px 16px',border:0,background:'transparent',color:'#222'});
      button.onpointerdown=e=>e.preventDefault();
      button.onclick=()=>{if(this.pending)this.selected({...this.pending,annotationType:type});this.bar.style.display='none';window.getSelection()?.removeAllRanges();};
      this.bar.append(button);
    }
    document.body.append(this.bar);
    document.addEventListener('selectionchange',()=>this.selection());
  }
  nodes() {
    const nodes=[], walk=document.createTreeWalker(this.root,NodeFilter.SHOW_TEXT,{acceptNode:n=>n.parentElement.closest('button,[data-moyue-ui],.katex-mathml')?NodeFilter.FILTER_REJECT:NodeFilter.FILTER_ACCEPT});
    while(walk.nextNode())nodes.push(walk.currentNode);return nodes;
  }
  text() {return this.nodes().map(n=>n.data).join('');}
  selection() {
    const s=window.getSelection();
    if(this.root.hidden||!s||s.isCollapsed||!s.rangeCount){this.bar.style.display='none';return;}
    const r=s.getRangeAt(0), nodes=this.nodes();
    if(!this.root.contains(r.startContainer)||!this.root.contains(r.endContainer)){this.bar.style.display='none';return;}
    let total=0,start=-1,end=-1;
    for(const n of nodes){if(n===r.startContainer)start=total+r.startOffset;if(n===r.endContainer)end=total+r.endOffset;total+=n.length;}
    const text=this.text();
    if(start<0||end<=start||end-start>32768){this.bar.style.display='none';return;}
    this.pending={anchor:{kind:'MARKDOWN',charOffset:start,endOffset:end,quote:text.slice(start,end),before:text.slice(Math.max(0,start-40),start),after:text.slice(end,end+40)},text:text.slice(start,end)};
    this.bar.style.display='flex';
  }
  locate(a,text) {
    const q=a.quote||'';if(!q)return null;
    if(!a.source && text.slice(a.charOffset,a.charOffset+q.length)===q)return [a.charOffset,a.charOffset+q.length];
    const found=[];let index=0;
    while((index=text.indexOf(q,index))>=0){
      if((!a.before||text.slice(Math.max(0,index-a.before.length),index)===a.before)&&(!a.after||text.slice(index+q.length,index+q.length+a.after.length)===a.after))found.push(index);
      index+=Math.max(1,q.length);if(found.length>1)break;
    }
    return found.length===1?[found[0],found[0]+q.length]:null;
  }
  clear() {this.root.querySelectorAll('mark[data-moyue-mark]').forEach(n=>n.replaceWith(...n.childNodes));this.root.normalize();}
  paint(range,color) {
    let offset=0;const nodes=this.nodes();let first=null;
    for(const node of nodes){const end=offset+node.length,left=Math.max(0,range[0]-offset),right=Math.min(node.length,range[1]-offset);
      if(left<right){const r=document.createRange();r.setStart(node,left);r.setEnd(node,right);const mark=document.createElement('mark');mark.dataset.moyueMark='true';mark.style.backgroundColor=color;mark.style.color='inherit';r.surroundContents(mark);first ||= mark;}
      offset=end;
    }return first;
  }
  set(items) {
    this.items=items;this.clear();const text=this.text(),colors={yellow:'#efc75266',green:'#6fcf9766',blue:'#6caff066',pink:'#f493b266'};
    for(const item of items){if(item.anchor.kind!=='MARKDOWN')continue;const range=this.locate(item.anchor,text);if(range)this.paint(range,colors[item.color]||colors.yellow);}
  }
  jump(anchor) {
    if(anchor.position && !anchor.quote){window.scrollTo(0,anchor.position.offset||0);return;}
    const range=this.locate(anchor,this.text());if(!range){this.missing();return;}
    const first=this.paint(range,'#ffb83e99');first?.scrollIntoView({block:'center'});
    clearTimeout(this.timer);this.timer=setTimeout(()=>this.set(this.items),2500);
  }
};
