# Retrieval diagnostic figure

[Held-out cell-ranking comparison](held-out-diagnostic.svg) is plotted from the
committed [actual evaluation record](../../research/evidence/course-retrieval-live.json),
not fabricated illustration data. It compares eight test queries over one unique
UIT notebook and must not be presented as independent multi-course validation.
The PNG is a portable preview of the SVG; labels and axes use English.

Regenerate with Python and Matplotlib (the recorded environment used 3.11.2):

```sh
python3 -m venv /tmp/uit-retrieval-plot-env
/tmp/uit-retrieval-plot-env/bin/pip install matplotlib==3.11.2
/tmp/uit-retrieval-plot-env/bin/python scripts/render-retrieval-benchmark.py
```

The script reads existing measurements and makes no university/provider request.
See the [evaluation method and caveats](../../research/COURSE-RETRIEVAL-EVALUATION.md).
