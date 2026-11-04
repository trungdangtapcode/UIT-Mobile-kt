#!/usr/bin/env python3
"""Plot reviewed actual retrieval measurements; never synthesize missing results."""
import json
from pathlib import Path
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt

root = Path(__file__).resolve().parents[1]
record = json.loads((root / 'docs/research/evidence/course-retrieval-live.json').read_text())
summary = record['summary']['test']
methods = ['token-and', 'bm25', 'semantic', 'hybrid']
labels = ['All-token baseline', 'BM25', 'Dense semantic', 'RRF hybrid']
colors = ['#94A3B8', '#0768B5', '#0F766E', '#805B00']
fig, axes = plt.subplots(1, 3, figsize=(10.6, 3.5), sharey=True)
for ax, metric, title in zip(axes, ['recall5', 'ndcg5', 'mrr10'], ['Recall@5', 'nDCG@5', 'MRR@10']):
    values = [summary[m][metric] for m in methods]
    ax.barh(range(4), values, color=colors, height=.58)
    for i, value in enumerate(values):
        ax.text(min(value + .025, .92), i, f'{value:.3f}', va='center', fontsize=9, color='#102A43')
    ax.set_xlim(0, 1.03)
    ax.set_xticks([0, .5, 1])
    ax.set_yticks(range(4), labels)
    ax.set_title(title, fontsize=12, loc='left', weight='bold', color='#102A43')
    ax.grid(axis='x', alpha=.15)
    ax.set_axisbelow(True)
    for spine in ax.spines.values():
        spine.set_visible(False)
axes[0].invert_yaxis()
fig.suptitle('One actual UIT notebook · 8 held-out diagnostic queries', x=.02, ha='left', fontsize=14, weight='bold', color='#102A43')
fig.text(.02, .015, 'Implementer-authored cell labels; 1 unique document / 103 passages. Not an independent multi-course benchmark.', fontsize=8, color='#52677D')
fig.tight_layout(rect=[0, .06, 1, .9])
out = root / 'docs/figures/retrieval'
out.mkdir(parents=True, exist_ok=True)
for suffix in ['svg', 'png']:
    fig.savefig(out / f'held-out-diagnostic.{suffix}', dpi=180, facecolor='white')
print('Rendered figures from the committed actual measurement record.')
