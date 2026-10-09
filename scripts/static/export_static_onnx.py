"""S0 export: LightGBM drebin_hybrid_v3 -> ONNX (onnxmltools) + rules/golden/meta for the app.
Requires: lightgbm onnxmltools onnx onnxruntime pandas. Outputs into the current folder."""
import json, ast, numpy as np, pandas as pd, lightgbm as lgb, onnx, onnxruntime as ort
from onnxmltools import convert_lightgbm
from onnxmltools.convert.common.data_types import FloatTensorType
import sys
# Usage: python export_static_onnx.py path/to/dhrashta-models/   (folder with static/ and scripts/)
S=(sys.argv[1].rstrip('/')+'/') if len(sys.argv)>1 else 'dhrashta-models/'
order=json.load(open(S+'static/feature_order_v3.json'))
feats=order['drebin']+order['modern']
b=lgb.Booster(model_file=S+'static/drebin_hybrid_v3.txt')
print('nfeat',b.num_feature(),'trees',b.num_trees())
df=pd.read_csv(S+'static/extracted_v6.csv')
miss=[f for f in feats if f not in df.columns]; print('missing cols',miss)
X=df[feats].apply(pd.to_numeric,errors='coerce').fillna(0).astype('float32').values
pref=b.predict(X)
onx=convert_lightgbm(b,initial_types=[('features',FloatTensorType([1,len(feats)]))],target_opset=15,zipmap=False)
onnx.save(onx,'static_lgbm.onnx')
s=ort.InferenceSession('static_lgbm.onnx',providers=['CPUExecutionProvider'])
print([(o.name,o.shape,o.type) for o in s.get_outputs()], sorted({n.op_type for n in onx.graph.node}))
po=np.array([s.run(None,{'features':X[i:i+1]})[1][0,1] for i in range(len(X))])
print('maxdiff',np.abs(po-pref).max())
# rules
src=open(S+'scripts/predict_v6_family.py').read();t=ast.parse(src);R={}
for n in t.body:
  if isinstance(n,ast.Assign) and isinstance(n.targets[0],ast.Name) and n.targets[0].id in ['STRONG_ALONE','V6_STRONG','WEAK','CO_OCCURRENCE','STRICT_TRIGGERS']:
    R[n.targets[0].id]=ast.literal_eval(n.value)
def boost(r):
  x=0.0
  for g in ['STRONG_ALONE','V6_STRONG','WEAK']:
    for f,w in R[g].items():
      if r.get(f,0)==1:x+=w
  if any(r.get(t,0)==1 for t in R['STRICT_TRIGGERS']):
    for f,w in R['CO_OCCURRENCE'].items():
      if r.get(f,0)==1:x+=w
  return x
allf=list(df.columns[2:])
rows=df[allf].to_dict('records')
bo=np.array([boost(r) for r in rows]); fin=np.minimum(po+bo,1)
y=df['_label'].values
for i in range(len(df)): print(y[i],f"{po[i]:.3f} +{bo[i]:.2f} = {fin[i]:.3f}",df['_filename'][i])
for t in [0.5,0.3,0.2,0.1]:
  p=fin>=t; print(t,'TP',(p&(y==1)).sum(),'FP',(p&(y==0)).sum(),'FN',(~p&(y==1)).sum(),'pos',(y==1).sum())
pb=po>=0.5; print('base@0.5 TP',(pb&(y==1)).sum(),'FP',(pb&(y==0)).sum())
json.dump(R,open('static_rules.json','w'),indent=1)
json.dump({'model_file':'static_lgbm.onnx','input_name':'features','n_features':len(feats),'feature_order':feats,
 'prob_output_index':1,'prob_column':1,'boost_only_features':[f for f in allf if f not in feats],
 'fusion':'S0=min(base+boost,1)','n_drebin':215,'source':'drebin_hybrid_v3.txt (LightGBM, 300 trees) via onnxmltools TreeEnsembleClassifier; CPU EP'},open('static_meta.json','w'),indent=1)
gold=[{'name':df['_filename'][i],'label':int(y[i]),'present':[f for f in allf if rows[i].get(f,0)==1],'base':float(po[i]),'boost':float(bo[i]),'final':float(fin[i])} for i in range(len(df))]
json.dump(gold,open('static_golden.json','w'))
