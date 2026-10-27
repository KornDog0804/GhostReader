// GhostReader paginated EPUB engine. No iframe scrolling or CSS-column offsets.
// Each visible page is a separately laid-out DOM fragment, sized to the viewport.
export class PageEngine {
  constructor(frame) { this.frame=frame; this.pages=[]; this.index=0; this.serial=0; }
  async load(html, css, opts, fraction=0) {
    const serial=++this.serial;
    const {width,height,margin,font,size,spacing,theme}=opts;
    const family=font==="serif"?"Georgia,serif":"system-ui,sans-serif";
    const safeCss=css.replace(/<\/style/gi,"");
    const shell=`<!doctype html><html><head><meta charset="utf-8"><style>
      html,body{margin:0;width:100%;height:100%;overflow:hidden;background:${theme.bg};color:${theme.ink}}
      #page{position:absolute;inset:0;overflow:hidden;padding:${margin}px;box-sizing:border-box;
      font:${size}px/${spacing} ${family};overflow-wrap:break-word}
      #page img,#page svg{max-width:100%;max-height:80%;object-fit:contain}
      #page p{margin:0 0 .7em} #page h1,#page h2,#page h3{line-height:1.25}
      #page *{color:inherit!important;background-color:transparent!important}
      #page a{color:${theme.link}!important}
      </style></head><body><main id="page"></main></body></html>`;
    await new Promise(resolve=>{this.frame.onload=resolve;this.frame.srcdoc=shell;});
    if(serial!==this.serial)return;
    const doc=this.frame.contentDocument, page=doc.getElementById("page");
    const source=new DOMParser().parseFromString(html,"text/html");
    // Extract readable blocks while keeping inline styling and images.
    const blocks=[];
    const walk=(node)=>{
      if(node.nodeType===3){if(node.textContent.trim()){const p=doc.createElement("p");p.textContent=node.textContent;blocks.push(p);}return;}
      if(node.nodeType!==1)return;
      const tag=node.tagName.toLowerCase();
      if(["script","style","head","nav"].includes(tag))return;
      if(["p","h1","h2","h3","h4","h5","h6","li","blockquote","pre","img","svg","table","hr"].includes(tag)){
        const clone=doc.importNode(node,true);
        clone.querySelectorAll?.("script,style").forEach(n=>n.remove());
        blocks.push(clone);return;
      }
      for(const child of node.childNodes)walk(child);
    };
    for(const node of source.body.childNodes)walk(node);
    const fits=()=>page.scrollHeight<=page.clientHeight+2;
    const results=[];let current=doc.createDocumentFragment();
    const finish=()=>{if(page.childNodes.length){results.push(page.innerHTML);page.replaceChildren();}};
    for(const block of blocks){
      page.append(block);
      if(fits())continue;
      page.removeChild(block);
      if(page.childNodes.length)finish();
      page.append(block);
      if(fits())continue;
      page.removeChild(block);
      // Split oversized text blocks at word boundaries, preserving all words.
      const original=block.textContent||"";
      if(!original.trim()){page.append(block);finish();continue;}
      const words=original.match(/\S+\s*/g)||[];
      let pos=0;
      while(pos<words.length){
        let lo=1,hi=words.length-pos,best=0;
        while(lo<=hi){
          const mid=(lo+hi)>>1;
          const test=block.cloneNode(false);
          test.textContent=words.slice(pos,pos+mid).join("");
          page.replaceChildren(test);
          if(fits()){best=mid;lo=mid+1;}else hi=mid-1;
        }
        if(!best)best=1;
        const piece=block.cloneNode(false);piece.textContent=words.slice(pos,pos+best).join("");
        page.replaceChildren(piece);finish();pos+=best;
      }
    }
    finish();
    this.pages=results.length?results:["<p></p>"];
    this.index=Math.max(0,Math.min(this.pages.length-1,Math.floor(fraction*this.pages.length)));
    this.show();
  }
  show(){const page=this.frame.contentDocument?.getElementById("page");if(page)page.innerHTML=this.pages[this.index]||"";}
  turn(n){const next=this.index+n;if(next<0||next>=this.pages.length)return false;this.index=next;this.show();return true;}
  get count(){return this.pages.length;}
}
