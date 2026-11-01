#!/usr/bin/env python3
"""Render reproducible workload illustrations from synthetic Kotlin engine output."""

from __future__ import annotations

import argparse
import csv
from pathlib import Path
import subprocess

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib import font_manager
import numpy as np

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "docs/figures/workload"
BLUE = "#0768B5"
NAVY = "#102A43"
MUTED = "#52677A"
AMBER = "#9F5A12"
RED = "#B73856"
TEAL = "#087E8B"
LIGHT = "#DDECF7"
GRAY = "#D7E0E8"


def setup() -> None:
    fonts = ROOT / "core/designsystem/src/main/res/font"
    for name in ("ibm_plex_sans_regular.ttf", "ibm_plex_sans_semibold.ttf"):
        font_manager.fontManager.addfont(str(fonts / name))
    plt.rcParams.update({
        "font.family": "IBM Plex Sans",
        "font.size": 11,
        "text.color": NAVY,
        "axes.labelcolor": NAVY,
        "axes.titlecolor": NAVY,
        "axes.titleweight": "semibold",
        "axes.edgecolor": "#9BAEBF",
        "xtick.color": MUTED,
        "ytick.color": MUTED,
        "mathtext.fontset": "stix",
        "svg.fonttype": "path",
        "svg.hashsalt": "uit-workload-algorithm-v1",
        "figure.facecolor": "white",
        "axes.facecolor": "white",
        "savefig.facecolor": "white",
    })


def load_data() -> tuple[dict[str, dict[str, np.ndarray]], dict[str, dict[str, int]]]:
    cases: dict[str, dict[str, int]] = {}
    with (OUT / "engine-examples.tsv").open() as source:
        for row in csv.DictReader(source, delimiter="\t"):
            cases.setdefault(row["case"], {})[row["key"]] = int(row["value"])
    grouped: dict[str, list[dict[str, str]]] = {}
    with (OUT / "engine-curves.tsv").open() as source:
        for row in csv.DictReader(source, delimiter="\t"):
            grouped.setdefault(row["scenario"], []).append(row)
    curves = {}
    for name, rows in grouped.items():
        curves[name] = {key: np.array([int(row[key]) for row in rows]) for key in ("minute", "demand", "lower", "upper")}
        assert np.array_equal(curves[name]["minute"], np.arange(601))
        assert np.all(curves[name]["lower"] <= curves[name]["upper"])
    assert set(curves) == {"known", "unknown"}
    return curves, cases


def save(fig, name: str, title: str, description: str) -> None:
    fig.savefig(OUT / f"{name}.png", dpi=160, bbox_inches="tight", pad_inches=0.16,
                metadata={"Software": "UIT Mobile workload figures", "Title": title, "Description": description})
    fig.savefig(OUT / f"{name}.svg", bbox_inches="tight", pad_inches=0.16,
                metadata={"Creator": "UIT Mobile workload figures", "Date": None, "Title": title, "Description": description})
    svg = OUT / f"{name}.svg"
    svg.write_text("\n".join(line.rstrip() for line in svg.read_text().splitlines()) + "\n")
    plt.close(fig)
    print(f"Rendered {name}.png and {name}.svg")


def header(fig, title: str, subtitle: str) -> None:
    fig.text(0.07, 0.98, title, fontsize=21, weight="semibold", va="top")
    fig.text(0.07, 0.935, subtitle, fontsize=11, color=MUTED, va="top")


def clean_axes(ax, grid: bool = False) -> None:
    ax.spines[["top", "right"]].set_visible(False)
    ax.tick_params(length=3)
    if grid:
        ax.grid(axis="y", color="#E4EAF0", linewidth=0.7)
        ax.set_axisbelow(True)


def clock(minute: float, origin_hour: int = 8) -> str:
    value = int(minute) + origin_hour * 60
    return f"{value // 60:02d}:{value % 60:02d}"


def equations() -> None:
    fig = plt.figure(figsize=(10.2, 13.5))
    fig.text(0.07, 0.965, "Workload projection · equation reference", fontsize=23, weight="semibold")
    fig.text(0.07, 0.93, "Algorithm v1 · minutes of entered work and recorded capacity", color=MUTED, fontsize=12)
    rows = [
        (0.87, "01  Personal planning cutoff", r"$t_i=d_i-60L$",
         "The official due timestamp d remains unchanged. L is the entered finish lead in minutes."),
        (0.72, "02  Cumulative demand and unknown effort",
         r"$D(t)=\sum_{i\in I(t),\ e_i\ne\mathrm{null}}e_i$" + "\n" + r"$M(t)=\left|\{i\in I(t):e_i=\mathrm{null}\}\right|$",
         "I(t): upcoming, unsubmitted records with cutoff at or before t. Blank effort stays unknown."),
        (0.49, "03  Daily session and budget capacity",
         r"$k_d(t)=\min\!\left(n_d(t),\left\lfloor\frac{B_d}{m}\right\rfloor\right)$" + "\n" +
         r"$U_d(t)=\min\!\left(B_d,\sum_{j=1}^{k_d(t)}g_{d,(j)}(t)\right)$",
         "Eligible gaps are clipped at t, at least m minutes long, and sorted from largest to smallest."),
        (0.25, "04  Checkpoint capacity and known shortfall",
         r"$C_s(t)=\sum_{d\in H}U_{d,s}(t)$" + "\n" + r"$D(t)>C_{\mathrm{optimistic}}(t)\ \Rightarrow\ \mathrm{known\ shortfall}$",
         "s is the conservative or optimistic scenario. Missing information retains uncertainty."),
    ]
    for y, label, formula, note in rows:
        fig.text(0.07, y, label, fontsize=14, weight="semibold", color=BLUE)
        for index, line in enumerate(formula.splitlines()):
            fig.text(0.09, y - 0.045 - index * 0.07, line, fontsize=23, va="top")
        note_y = y - (0.195 if "\n" in formula else 0.10)
        fig.text(0.07, note_y, note, fontsize=10.5, color=MUTED, wrap=True)
    fig.text(0.07, 0.02, "Independent cutoff bounds do not construct a jointly feasible study-session schedule.", fontsize=11, weight="semibold")
    save(fig, "equations", "Workload algorithm equations", "Rendered formulas for cutoff, entered demand, missing estimates, minimum-session capacity and scenario classification. Full definitions are in WORKLOAD-ALGORITHM.md.")


def pressure(curves, cases) -> None:
    fig, axes = plt.subplots(2, 1, figsize=(12, 9.1))
    header(fig, "Demand versus capacity at planning cutoffs",
           "Synthetic Monday · 08:00–18:00 window · 240 min budget · 30 min minimum · lab busy 08:00–10:00")
    fig.subplots_adjust(left=0.09, right=0.97, top=0.83, bottom=0.15, hspace=0.75)
    for ax, name, title in zip(axes, ("known", "unknown"), ("A · Recorded durations", "B · An exam starts at 11:00; its end is unknown")):
        data = curves[name]
        x = data["minute"]
        ax.axvspan(0, 120, color=GRAY, alpha=0.35)
        if name == "unknown":
            ax.fill_between(x, data["lower"], data["upper"], step="post", color=LIGHT, alpha=0.85)
            ax.step(x, data["lower"], where="post", color=TEAL, linewidth=2, linestyle="--", label="Conservative capacity")
        ax.step(x, data["upper"], where="post", color=BLUE, linewidth=2, label="Optimistic capacity" if name == "unknown" else "Recorded capacity")
        ax.step(x, data["demand"], where="post", color=AMBER, linewidth=2.2, label="Cumulative due demand")
        ax.set_title(title, loc="left", fontsize=13, pad=35)
        ax.legend(loc="lower left", bbox_to_anchor=(0, 1.01), ncol=3, frameon=False, fontsize=10, borderaxespad=0)
        ax.set_xlim(0, 600); ax.set_ylim(0, 275)
        ticks = np.arange(0, 601, 120)
        ax.set_xticks(ticks, [clock(t) for t in ticks])
        ax.set_yticks([0, 60, 120, 180, 240]); ax.set_ylabel("Minutes before cutoff")
        ax.axvline(240, color=RED, linewidth=1, linestyle=":")
        ax.vlines(240, data["upper"][240], data["demand"][240], color=RED, linewidth=3)
        ax.scatter([240], [data["demand"][240]], color=RED, s=35, zorder=5)
        deficit = int(data["demand"][240] - data["upper"][240])
        ax.annotate(f"12:00: {deficit} min shortfall", xy=(240, data["demand"][240]), xytext=(245, 247), color=RED, fontsize=11,
                    arrowprops={"arrowstyle": "->", "color": RED, "lw": 0.9})
        if name == "known":
            ax.scatter([420], [data["demand"][420]], facecolor="white", edgecolor=BLUE, s=42, linewidth=1.5, zorder=6)
            ax.annotate("15:00 equality does not repair\nthe earlier 12:00 deficit", xy=(420, data["demand"][420]), xytext=(355, 65), fontsize=10,
                        arrowprops={"arrowstyle": "->", "color": MUTED, "lw": 0.8})
        else:
            ax.annotate(f"15:00 capacity: {data['lower'][420]}–{data['upper'][420]} min", xy=(420, 130), xytext=(350, 22), fontsize=10,
                        arrowprops={"arrowstyle": "->", "color": MUTED, "lw": 0.8})
        clean_axes(ax, grid=True)
    axes[-1].set_xlabel("UIT time · GMT+07")
    fig.text(0.09, 0.075, "D(t) describes work due by each cutoff, not when studying starts. Both tasks are known from 08:00.", fontsize=10.5, color=MUTED)
    fig.text(0.09, 0.045, "Panel B enters 0 exam-preparation minutes; attendance duration remains unknown. Values are sampled from WorkloadEngine every minute.", fontsize=10, color=MUTED)
    save(fig, "cutoff-pressure", "Cumulative workload demand and recorded capacity", "Two engine-derived scenarios show a 60-minute noon deficit, later equality, and conservative/optimistic capacity when an exam end is unknown. Synthetic inputs only.")


def union_and_fragments(cases) -> None:
    fig, axes = plt.subplots(1, 2, figsize=(13.1, 7.1), gridspec_kw={"width_ratios": [1.15, 1]})
    header(fig, "How busy intervals and fragmented gaps change capacity",
           "Executable synthetic examples · the time window is not counted as usable study time by itself")
    fig.subplots_adjust(left=0.16, right=0.97, top=0.78, bottom=0.26, wspace=0.48)
    union = cases["union"]
    ax = axes[0]
    a, b = (union["a_start"], union["a_end"]), (union["b_start"], union["b_end"])
    assert a[0] <= b[0] <= a[1]
    rows = [(4, [a], MUTED), (3, [b], MUTED), (2, [(min(a[0], b[0]), max(a[1], b[1]))], BLUE),
            (1, [(union["union_start"], union["union_end"])], TEAL),
            (0, [(0, union["union_start"]), (union["union_end"], union["window_end"])], BLUE)]
    for y, intervals, color in rows:
        for start, end in intervals:
            ax.barh(y, end - start, left=start, height=0.48, color=color, alpha=0.9)
            ax.text((start + end) / 2, y, f"{end-start} min", ha="center", va="center", color="white", fontsize=10)
    ax.set_yticks(range(5), ["Free gaps", "+15 min buffers", "Union", "Busy B", "Busy A"])
    ax.set_xlim(0, 240); ax.set_ylim(-0.7, 4.7)
    ax.set_xticks([0, 60, 120, 180, 240], [clock(t) for t in [0, 60, 120, 180, 240]], rotation=30, ha="right")
    ax.set_title("A · Union first; subtract once", loc="left", fontsize=13, pad=18)
    ax.set_xlabel("UIT time · GMT+07")
    clean_axes(ax)
    ax = axes[1]
    f = cases["fragments"]
    for y, budget, capacity in [(1, f["budget_a"], f["capacity_a"]), (0, f["budget_b"], f["capacity_b"])]:
        for index, (start, end) in enumerate([(f["first_start"], f["first_end"]), (f["second_start"], f["second_end"]) ]):
            active = index == 0 or budget >= 2 * f["minimum"]
            ax.barh(y, end - start, left=start, height=0.40, color=BLUE if active else LIGHT, edgecolor=BLUE if active else "#9BAEBF", hatch=None if active else "///")
            ax.text((start+end)/2, y, f"{end-start}", color="white" if active else MUTED, ha="center", va="center", fontsize=11)
        ax.text(0, y-0.32, f"B = {budget}, m = {f['minimum']}  →  usable = {capacity} min", fontsize=11, color=NAVY)
    ax.set_yticks([0, 1], [f"Budget {f['budget_b']} min", f"Budget {f['budget_a']} min"])
    ax.set_xlim(0, 100); ax.set_ylim(-0.8, 1.8)
    ax.set_xticks([0, 30, 60, 90], [clock(t) for t in [0, 30, 60, 90]], rotation=30, ha="right")
    ax.set_title("B · A budget cannot join short fragments", loc="left", fontsize=13, pad=18)
    ax.set_xlabel("Available gaps shown; all other time is busy")
    clean_axes(ax)
    fig.text(0.16, 0.13, f"Panel A: the engine returns {union['capacity']} usable minutes after union, buffers and session filtering.", fontsize=10.5, color=MUTED)
    fig.text(0.16, 0.085, "Panel B shows one maximizing choice. It illustrates a capacity bound, not a study-session assignment.", fontsize=10.5, color=MUTED)
    save(fig, "busy-union-and-fragments", "Busy interval union and fragmented daily budgets", "Overlapping busy intervals are unioned and buffered before subtraction. Two 30-minute gaps yield only 30 usable minutes with a 50-minute budget and a 30-minute minimum session, or 60 with a 60-minute budget.")


def prefix_counterexample(cases) -> None:
    case = cases["counterexample"]
    fig = plt.figure(figsize=(11.8, 8.2))
    header(fig, "Why passing each cutoff does not construct a schedule",
           f"Synthetic counterexample · daily budget B = {case['budget']} min · minimum session m = {case['minimum']} min")
    ax = fig.add_axes([0.10, 0.61, 0.85, 0.20])
    ax.barh(0, 240, left=0, height=0.42, color=GRAY)
    for start, end in [(case["first_start"], case["first_end"]), (case["second_start"], case["second_end"])]:
        ax.barh(0, end-start, left=start, height=0.42, color=BLUE)
        ax.text((start+end)/2, 0, f"{end-start} min free", color="white", ha="center", va="center", fontsize=11)
        ax.axvline(end, ymin=0.10, ymax=0.95, color=AMBER, linestyle=":", linewidth=1.3)
    ax.text(case["first_end"]+3, 0.46, "Cutoff 1", fontsize=10, color=AMBER)
    ax.text(case["second_end"]+3, 0.46, "Cutoff 2", fontsize=10, color=AMBER)
    ax.set_xlim(0, 240); ax.set_ylim(-0.65, 0.8); ax.set_yticks([])
    ticks = [0, 30, 60, 120, 170, 240]
    ax.set_xticks(ticks, [clock(t) for t in ticks]); ax.set_xlabel("UIT time · gray intervals are busy")
    clean_axes(ax)
    table_ax = fig.add_axes([0.07, 0.23, 0.48, 0.25]); table_ax.axis("off")
    table_ax.set_title("Independent engine checks", loc="left", fontsize=13, pad=20)
    cells = [[clock(case["first_end"]), str(case["first_demand"]), str(case["first_capacity"]), "Within budget"],
             [clock(case["second_end"]), str(case["second_demand"]), str(case["second_capacity"]), "Within budget"]]
    table = table_ax.table(cellText=cells, colLabels=["Cutoff", "D (min)", "C (min)", "Result"], loc="upper left", cellLoc="center", colWidths=[.18, .18, .18, .36])
    table.auto_set_font_size(False); table.set_fontsize(11); table.scale(1, 1.8)
    for (row, _), cell in table.get_celld().items():
        cell.set_edgecolor("#CBD5DF")
        if row == 0: cell.set_facecolor(LIGHT); cell.set_text_props(weight="semibold", color=NAVY)
    remainder = case["budget"] - case["first_demand"]
    fig.text(0.59, 0.48, "Joint placement still fails", color=RED, fontsize=15, weight="semibold")
    fig.text(0.59, 0.415, rf"$B-D(t_1)={case['budget']}-{case['first_demand']}={remainder}$", fontsize=23)
    fig.text(0.59, 0.355, rf"${remainder}<m={case['minimum']}$", fontsize=23, color=RED)
    fig.text(0.59, 0.27, f"The early task uses the entire {case['first_demand']} min gap.\nOnly {remainder} budget minutes remain;\na later session needs at least {case['minimum']}.", fontsize=11.5, linespacing=1.5)
    fig.text(0.08, 0.125, "A later, larger gap cannot undo an earlier use of the daily budget.", fontsize=12, weight="semibold")
    fig.text(0.08, 0.075, "The feature reports conditional checkpoint pressure. It does not claim to produce a feasible study-session schedule.", fontsize=10.5, color=MUTED)
    save(fig, "prefix-counterexample", "Independent cutoff checks and a joint-session counterexample", "Both engine checkpoints have enough independently calculated capacity, but a 50-minute daily budget and a 30-minute minimum cannot fit both an early 30-minute session and a later minimum-sized session.")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--skip-export", action="store_true", help="Render the committed TSV data without rerunning Kotlin.")
    args = parser.parse_args()
    if not args.skip_export:
        completed = subprocess.run([str(ROOT / "gradlew"), ":core:domain:exportWorkloadFigureData", "--console=plain"], cwd=ROOT, text=True, capture_output=True)
        if completed.returncode:
            raise SystemExit(completed.stdout[-8000:] + completed.stderr[-4000:])
    setup()
    curves, cases = load_data()
    equations()
    pressure(curves, cases)
    union_and_fragments(cases)
    prefix_counterexample(cases)


if __name__ == "__main__":
    main()
