# Export DocCornerNet (mobilenetv2 a=0.35, 256) to a float16 TFLite with one [1,9] output: 8 coords + sigmoid score.
import sys, json
sys.path.insert(0, sys.argv[1])
import tensorflow as tf, numpy as np
from model import create_model
ck = sys.argv[2]; out = sys.argv[3]
cfg = json.load(open(f'{ck}/config.json'))
m = create_model(backbone=cfg['backbone'], alpha=cfg['alpha'], fpn_ch=cfg['fpn_ch'], simcc_ch=cfg['simcc_ch'],
                 img_size=cfg['img_size'], num_bins=cfg['num_bins'], tau=cfg['tau'], backbone_weights=None)
m.load_weights(f'{ck}/best_model.weights.h5')
inp = tf.keras.Input((cfg['img_size'], cfg['img_size'], 3), batch_size=1)
o = m(inp, training=False)
y = tf.keras.layers.Concatenate()([o['coords'], tf.keras.activations.sigmoid(o['score_logit'])])
fm = tf.keras.Model(inp, y)
conv = tf.lite.TFLiteConverter.from_keras_model(fm)
conv.optimizations = [tf.lite.Optimize.DEFAULT]
conv.target_spec.supported_types = [tf.float16]
open(out, 'wb').write(conv.convert())
print('ok', cfg['img_size'])
