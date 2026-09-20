/* TaxData V6.6 LOCAL-FIRST STORAGE
 * Master business workspace is stored in this browser profile (IndexedDB).
 * Server workspace is temporary per login session and is restored from / snapshotted back to IndexedDB.
 */
const LocalWorkspaceStore=(()=>{
  const DB='taxdata-local-first-v1', VER=1, WS='workspaces', HANDLES='directory-handles';
  let dbPromise=null;
  async function migratePreviousWorkspace(db){
    try{
      if(!indexedDB.databases)return;
      const ownCount=await new Promise((resolve,reject)=>{const r=db.transaction(WS,'readonly').objectStore(WS).count();r.onsuccess=()=>resolve(r.result||0);r.onerror=()=>reject(r.error)});
      if(ownCount>0)return;
      const list=await indexedDB.databases();
      const candidates=list.map(x=>x.name).filter(n=>n&&n!==DB&&n.endsWith('-local-first-v1'));
      for(const name of candidates){
        const oldDb=await new Promise((resolve,reject)=>{const r=indexedDB.open(name);r.onsuccess=()=>resolve(r.result);r.onerror=()=>reject(r.error)});
        try{
          for(const store of [WS,HANDLES]){
            if(!oldDb.objectStoreNames.contains(store))continue;
            const rows=await new Promise((resolve,reject)=>{const r=oldDb.transaction(store,'readonly').objectStore(store).getAll();r.onsuccess=()=>resolve(r.result||[]);r.onerror=()=>reject(r.error)});
            if(rows.length)await new Promise((resolve,reject)=>{const t=db.transaction(store,'readwrite'),os=t.objectStore(store);rows.forEach(x=>os.put(x));t.oncomplete=resolve;t.onerror=()=>reject(t.error)});
          }
        }finally{oldDb.close()}
        indexedDB.deleteDatabase(name);
      }
    }catch(e){console.warn('TaxData workspace migration skipped',e)}
  }
  const openDb=()=>dbPromise||(dbPromise=new Promise((resolve,reject)=>{
    if(!('indexedDB' in window))return reject(new Error('Bu brauzerdə IndexedDB dəstəklənmir.'));
    const r=indexedDB.open(DB,VER);
    r.onupgradeneeded=()=>{const db=r.result;if(!db.objectStoreNames.contains(WS))db.createObjectStore(WS,{keyPath:'userId'});if(!db.objectStoreNames.contains(HANDLES))db.createObjectStore(HANDLES,{keyPath:'userId'});};
    r.onsuccess=async()=>{try{await migratePreviousWorkspace(r.result)}finally{resolve(r.result)}};r.onerror=()=>reject(r.error);
  }));
  const tx=(store,mode,fn)=>openDb().then(db=>new Promise((resolve,reject)=>{const t=db.transaction(store,mode),s=t.objectStore(store);let result;try{result=fn(s)}catch(e){reject(e);return}t.oncomplete=()=>resolve(result?.result??result);t.onerror=()=>reject(t.error);t.onabort=()=>reject(t.error||new Error('IndexedDB əməliyyatı dayandırıldı.'));}));
  const get=(store,key)=>openDb().then(db=>new Promise((resolve,reject)=>{const r=db.transaction(store,'readonly').objectStore(store).get(key);r.onsuccess=()=>resolve(r.result||null);r.onerror=()=>reject(r.error)}));
  const put=(store,value)=>tx(store,'readwrite',s=>s.put(value));
  const del=(store,key)=>tx(store,'readwrite',s=>s.delete(key));
  const fmtBytes=n=>{n=Number(n||0);if(n<1024)return `${n} B`;if(n<1048576)return `${(n/1024).toFixed(1)} KB`;if(n<1073741824)return `${(n/1048576).toFixed(1)} MB`;return `${(n/1073741824).toFixed(2)} GB`};
  const backupName=user=>`taxdata_${String(user?.username||'workspace').replace(/[^a-zA-Z0-9._-]/g,'_')}_workspace.zip`;

  async function requestPersistentStorage(){try{if(navigator.storage?.persist)await navigator.storage.persist()}catch{}}
  async function getRecord(userId){return get(WS,userId)}
  const userProfile=user=>({username:String(user?.username||''),whatsapp:String(user?.whatsapp||''),etaxesPhone:String(user?.etaxesPhone||''),etaxesUserId:String(user?.etaxesUserId||''),etaxesTin:String(user?.etaxesTin||''),profileUpdatedAt:user?.profileUpdatedAt||null});
  async function saveRecord(userId,blob,extra={}){const old=await getRecord(userId)||{};const rec={...old,...extra,userId,blob,bytes:blob?.size||0,updatedAt:new Date().toISOString(),deviceId:localStorage.getItem('taxdata.deviceId')||''};await put(WS,rec);return rec}
  async function syncUserProfile(user){if(!user?.id)return null;const old=await getRecord(user.id)||{};const profile=userProfile(user);const same=JSON.stringify(old.userProfile||{})===JSON.stringify(profile);if(same)return old;const rec={...old,userId:user.id,username:user.username||old.username||'',userProfile:profile,profileSyncedAt:new Date().toISOString(),deviceId:localStorage.getItem('taxdata.deviceId')||old.deviceId||''};await put(WS,rec);return rec}
  async function getDirectoryRecord(userId){return get(HANDLES,userId)}
  async function saveDirectoryRecord(userId,handle){return put(HANDLES,{userId,handle,name:handle?.name||'',updatedAt:new Date().toISOString()})}
  async function clearDirectory(userId){return del(HANDLES,userId)}
  async function ensurePermission(handle,ask=false){if(!handle)return false;try{const opts={mode:'readwrite'};if((await handle.queryPermission(opts))==='granted')return true;if(ask&&await handle.requestPermission(opts)==='granted')return true}catch{}return false}
  async function chooseDirectory(userId){if(!window.showDirectoryPicker)throw new Error('Lokal qovluq seçimi Chrome/Edge HTTPS rejimində dəstəklənir.');const h=await window.showDirectoryPicker({mode:'readwrite'});if(!(await ensurePermission(h,true)))throw new Error('Qovluğa yazma icazəsi verilmədi.');await saveDirectoryRecord(userId,h);return h}
  async function dir(userId,ask=false){const r=await getDirectoryRecord(userId);if(!r?.handle)return null;return await ensurePermission(r.handle,ask)?r.handle:null}
  async function childDir(root,name){const safe=String(name||'TaxData').replace(/[\\/:*?"<>|]/g,'_').slice(0,100)||'TaxData';return root.getDirectoryHandle(safe,{create:true})}
  async function userFolder(userId,folder='TaxData'){const root=await dir(userId,false);if(!root)return null;const users=await childDir(root,'TaxData_Users');const userRoot=await childDir(users,String(userId||'unknown'));return folder?childDir(userRoot,folder):userRoot}
  async function writeBlob(userId,name,blob,folder='TaxData'){try{const target=await userFolder(userId,folder);if(!target)return false;const fh=await target.getFileHandle(String(name).replace(/[\\/:*?"<>|]/g,'_'),{create:true});const w=await fh.createWritable();await w.write(blob);await w.close();return true}catch(e){console.warn('Local file write failed',e);return false}}
  async function writeVersionedBackup(user,blob,reason='auto'){const target=await userFolder(user.id,'TaxData_Backup');if(!target)return false;const current=backupName(user);await writeBlob(user.id,current,blob,'TaxData_Backup');const rec=await getRecord(user.id)||{};const now=Date.now(),last=Date.parse(rec.lastFolderVersionAt||'')||0;const important=/manual|logout|import|cloud|password|qovluq/i.test(String(reason||''));if(!important&&now-last<15*60*1000)return true;const stamp=new Date(now).toISOString().replace(/[:.]/g,'-');const prefix=current.replace(/\.zip$/i,'');const versionName=`${prefix}_${stamp}.zip`;await writeBlob(user.id,versionName,blob,'TaxData_Backup');try{const versions=[];for await(const [name,handle] of target.entries()){if(handle.kind==='file'&&name.startsWith(prefix+'_')&&name.endsWith('.zip'))versions.push(name)}versions.sort().reverse();for(const name of versions.slice(10))await target.removeEntry(name)}catch(e){console.warn('Backup version pruning skipped',e)}await saveRecord(user.id,blob,{username:user.username,reason,lastFolderVersionAt:new Date(now).toISOString()});return true}
  async function fetchBlob(url){const r=await fetch(url,{credentials:'same-origin'});if(!r.ok){let m=`HTTP ${r.status}`;try{const d=await r.json();m=d.error||d.message||m}catch{}throw new Error(m)}return r.blob()}
  async function saveUrl(userId,url,name,folder){const b=await fetchBlob(url);if(!(await writeBlob(userId,name,b,folder)))throw new Error('Lokal qovluq seçilməyib və ya yazma icazəsi yoxdur.');return b.size}

  async function cloudStatus(){try{const r=await fetch('/api/cloud-workspace/status',{credentials:'same-origin',cache:'no-store'});if(!r.ok)return {enabled:false,hasBackup:false};return r.json()}catch{return {enabled:false,hasBackup:false}}}
  async function ensureServerSession(user){
    await requestPersistentStorage();
    await syncUserProfile(user);
    const st=await fetch('/api/local-workspace/status',{credentials:'same-origin'}).then(async r=>{if(!r.ok)throw new Error((await r.json().catch(()=>({}))).error||`HTTP ${r.status}`);return r.json()});
    if(st.initialized)return {restored:false,status:st,record:await getRecord(user.id),source:'session'};
    const rec=await getRecord(user.id);
    if(rec?.blob?.size){const fd=new FormData();fd.append('file',rec.blob,backupName(user));const r=await fetch('/api/local-workspace/restore',{method:'POST',credentials:'same-origin',body:fd});if(!r.ok){const d=await r.json().catch(()=>({}));throw new Error(d.error||'Lokal backup server sessiyasına açıla bilmədi.')}return {restored:true,status:await r.json(),record:rec,source:'indexeddb'};}
    const cs=await cloudStatus();
    if(cs.enabled&&cs.hasBackup){const r=await fetch('/api/cloud-workspace/restore-latest',{method:'POST',credentials:'same-origin'});if(!r.ok){const d=await r.json().catch(()=>({}));throw new Error(d.error||'Cloud backup yeni kompüterə bərpa edilə bilmədi.')}const restored=await r.json();const local=await snapshot(user,{writeFolder:false,reason:'cloud-restore'});return {restored:true,status:restored,record:local,source:'cloud'};}
    const r=await fetch('/api/local-workspace/initialize',{method:'POST',credentials:'same-origin'});if(!r.ok){const d=await r.json().catch(()=>({}));throw new Error(d.error||'Lokal iş sahəsi yaradıla bilmədi.')}const init=await r.json();await snapshot(user,{writeFolder:false,reason:'initial'});return {restored:false,status:init,record:await getRecord(user.id),source:'new'};
  }

  async function snapshot(user,{writeFolder=true,reason='auto'}={}){
    if(!user?.id)throw new Error('İstifadəçi müəyyən edilməyib.');
    const r=await fetch('/api/local-workspace/snapshot',{credentials:'same-origin',cache:'no-store'});if(!r.ok){const d=await r.json().catch(()=>({}));throw new Error(d.error||`Backup alınmadı: HTTP ${r.status}`)}const blob=await r.blob();const rec=await saveRecord(user.id,blob,{username:user.username,reason,userProfile:userProfile(user),profileSyncedAt:new Date().toISOString()});
    if(writeFolder){const h=await dir(user.id,false);if(h)await writeVersionedBackup(user,blob,reason);}
    return rec;
  }
  async function cloudBackup(reason='auto',forceNewVersion=false){const r=await fetch('/api/cloud-workspace/backup',{method:'POST',credentials:'same-origin',headers:{'Content-Type':'application/json'},body:JSON.stringify({reason,forceNewVersion})});if(!r.ok){const d=await r.json().catch(()=>({}));throw new Error(d.error||`Cloud backup alınmadı: HTTP ${r.status}`)}return r.json()}
  async function restoreCloudLatest(user){const r=await fetch('/api/cloud-workspace/restore-latest',{method:'POST',credentials:'same-origin'});if(!r.ok){const d=await r.json().catch(()=>({}));throw new Error(d.error||'Cloud backup bərpa edilmədi.')}const d=await r.json();const rec=await snapshot(user,{writeFolder:true,reason:'cloud-restore'});return {...d,record:rec}}
  async function downloadCloudLatest(user){const r=await fetch('/api/cloud-workspace/latest.zip',{credentials:'same-origin',cache:'no-store'});if(!r.ok){const d=await r.json().catch(()=>({}));throw new Error(d.error||'Cloud backup endirilə bilmədi.')}const blob=await r.blob();const a=document.createElement('a');a.href=URL.createObjectURL(blob);a.download=`taxdata_${String(user?.username||'workspace').replace(/[^a-zA-Z0-9._-]/g,'_')}_cloud_workspace.zip`;document.body.appendChild(a);a.click();setTimeout(()=>{URL.revokeObjectURL(a.href);a.remove()},1000);return blob.size}

  async function exportFullTransfer(user,password,browserSettings={}){
    if(!user?.id)throw new Error('İstifadəçi müəyyən edilməyib.');
    if(String(password||'').length<10)throw new Error('Tam Transfer parolu ən azı 10 simvol olmalıdır.');
    const r=await fetch('/api/transfer-backup/export',{method:'POST',credentials:'same-origin',cache:'no-store',headers:{'Content-Type':'application/json'},body:JSON.stringify({password,browserSettings})});
    if(!r.ok){const d=await r.json().catch(()=>({}));throw new Error(d.error||d.message||`Tam Transfer Backup yaradılmadı: HTTP ${r.status}`)}
    const blob=await r.blob();
    const cd=r.headers.get('Content-Disposition')||'';let name=`taxdata_${String(user.username||'workspace').replace(/[^a-zA-Z0-9._-]/g,'_')}_tam_transfer.tdbackup`;
    const m=cd.match(/filename\*=UTF-8''([^;]+)/i);if(m)try{name=decodeURIComponent(m[1])}catch{}
    const a=document.createElement('a');a.href=URL.createObjectURL(blob);a.download=name;document.body.appendChild(a);a.click();setTimeout(()=>{URL.revokeObjectURL(a.href);a.remove()},1000);return {bytes:blob.size,name};
  }

  async function restoreFullTransfer(user,file,password){
    if(!user?.id)throw new Error('İstifadəçi müəyyən edilməyib.');
    if(!file)throw new Error('Tam Transfer backup faylı seçilməyib.');
    if(!/\.tdbackup$/i.test(file.name||''))throw new Error('Tam Transfer faylı .tdbackup formatında olmalıdır.');
    if(String(password||'').length<10)throw new Error('Tam Transfer parolu ən azı 10 simvol olmalıdır.');
    const fd=new FormData();fd.append('file',file,file.name);fd.append('password',password);
    const r=await fetch('/api/transfer-backup/restore',{method:'POST',credentials:'same-origin',cache:'no-store',body:fd});
    if(!r.ok){const d=await r.json().catch(()=>({}));throw new Error(d.error||d.message||'Tam Transfer Backup bərpa edilmədi.')}
    const data=await r.json();
    const rec=await snapshot(user,{writeFolder:true,reason:'tam-transfer-restore'});
    return {...data,record:rec};
  }

  async function importBackup(user,file){if(!file)throw new Error('Backup ZIP seçilməyib.');if(!/\.zip$/i.test(file.name||''))throw new Error('Backup ZIP formatında olmalıdır.');const old=await getRecord(user.id);const fd=new FormData();fd.append('file',file,file.name||backupName(user));const r=await fetch('/api/local-workspace/restore',{method:'POST',credentials:'same-origin',body:fd});if(!r.ok){const d=await r.json().catch(()=>({}));if(old?.blob?.size){try{const back=new FormData();back.append('file',old.blob,backupName(user));await fetch('/api/local-workspace/restore',{method:'POST',credentials:'same-origin',body:back})}catch{}}throw new Error(d.error||'Backup bərpa edilmədi.')}const data=await r.json();await saveRecord(user.id,file,{username:user.username,reason:'manual-import',userProfile:userProfile(user),profileSyncedAt:new Date().toISOString()});return data}
  async function exportBackup(user){let rec=await getRecord(user.id);if(!rec?.blob?.size)rec=await snapshot(user,{writeFolder:false,reason:'manual-export'});const a=document.createElement('a');a.href=URL.createObjectURL(rec.blob);a.download=backupName(user);document.body.appendChild(a);a.click();setTimeout(()=>{URL.revokeObjectURL(a.href);a.remove()},1000);return rec}
  async function copyLatestBackupToFolder(user){let rec=await getRecord(user.id);if(!rec?.blob?.size)rec=await snapshot(user,{writeFolder:false});const h=await dir(user.id,true);if(!h)throw new Error('Lokal qovluğa icazə verilmədi.');await writeBlob(user.id,backupName(user),rec.blob,'TaxData_Backup');return rec.bytes}
  async function info(userId){const rec=await getRecord(userId),dr=await getDirectoryRecord(userId);let estimate=null,writable=false;try{estimate=await navigator.storage?.estimate?.()}catch{}try{writable=dr?.handle?await ensurePermission(dr.handle,false):false}catch{}return {bytes:rec?.bytes||0,updatedAt:rec?.updatedAt||'',folderName:dr?.name||'',hasFolder:!!dr?.handle,folderWritable:writable,storageUsage:estimate?.usage||0,storageQuota:estimate?.quota||0,formatted:fmtBytes(rec?.bytes||0)}}

  return {ensureServerSession,snapshot,syncUserProfile,cloudStatus,cloudBackup,restoreCloudLatest,downloadCloudLatest,exportFullTransfer,restoreFullTransfer,importBackup,exportBackup,chooseDirectory,clearDirectory,copyLatestBackupToFolder,saveUrl,writeBlob,fetchBlob,info,fmtBytes,backupName};
})();
