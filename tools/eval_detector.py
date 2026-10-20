# Scores app/src/main/assets/doccorner256.tflite on labelled photos, the way the app runs it.
# Usage: python tools/eval_detector.py labels.json
# labels.json: [{"file": "path.jpg", "gt": [[x,y] tl, tr, br, bl] normalized, or null for "no document"}]
# Needs: pip install opencv-python-headless numpy ai-edge-litert
import sys, json, os, numpy as np, cv2
from ai_edge_litert.interpreter import Interpreter

MODEL = os.path.join(os.path.dirname(__file__), '../app/src/main/assets/doccorner256.tflite')
it = Interpreter(MODEL); it.allocate_tensors()
inp, out = it.get_input_details()[0], it.get_output_details()[0]

def detect(path):
    bgr = cv2.imread(path); h, w = bgr.shape[:2]; s = min(1, 900 / max(w, h))   # ImageIO.decode(file, 900)
    bgr = cv2.resize(bgr, (int(w * s), int(h * s)), interpolation=cv2.INTER_AREA)
    x = cv2.resize(cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB), (256, 256), interpolation=cv2.INTER_LINEAR) / 255.0
    x = ((x - [0.485, 0.456, 0.406]) / [0.229, 0.224, 0.225]).astype(np.float32)
    it.set_tensor(inp['index'], x[None]); it.invoke(); y = it.get_tensor(out['index'])[0]
    return None if y[8] < 0.5 else y[:8].reshape(4, 2)

def iou(q, g, n=400):
    a = np.zeros((n, n), np.uint8); b = a.copy()
    cv2.fillPoly(a, [(np.array(q) * n).round().astype(np.int32)], 1)
    cv2.fillPoly(b, [(np.array(g) * n).round().astype(np.int32)], 1)
    return (a & b).sum() / max(1, (a | b).sum())

labels = json.load(open(sys.argv[1])); base = os.path.dirname(os.path.abspath(sys.argv[1]))
pos, neg = [], []
for l in labels:
    q = detect(os.path.join(base, l['file']))
    if l['gt'] is None: neg.append(q is not None)
    else: pos.append(0.0 if q is None else iou(q, l['gt'])); print(f"{pos[-1]:.3f}  {l['file']}")
if pos: print(f'documents: {len(pos)}  mean IoU {np.mean(pos):.3f}  IoU>=0.9 {np.mean(np.array(pos) >= .9):.1%}')
if neg: print(f'no-document photos: {len(neg)}  false boxes {np.mean(neg):.1%}')
