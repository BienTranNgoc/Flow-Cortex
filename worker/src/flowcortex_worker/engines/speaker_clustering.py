"""Speaker clustering for the `speakers` engine child.

Ported from 3D-Speaker (https://github.com/modelscope/3D-Speaker, commit 065629c,
`speakerlab/process/cluster.py` and `compressed_seg`), Copyright 3D-Speaker, Apache License 2.0;
attribution in `worker/docker/speakers-NOTICE.txt`. Changed from the original: k-means has a fixed
seed so the same audio gives the same labels, and the speaker count is always chosen here.
"""

from __future__ import annotations

from typing import Any

import numpy as np
import scipy.sparse.linalg
from scipy.cluster.hierarchy import fcluster, linkage
from scipy.spatial.distance import squareform
from sklearn.cluster import k_means
from sklearn.metrics.pairwise import cosine_similarity

# scikit-learn 1.0.2's default, which 3D-Speaker pins; since 1.4 the default is one init, which
# makes the labels wobble between runs.
KMEANS_N_INIT = 10
KMEANS_SEED = 0
# Below this many chunks agglomerative clustering replaces the spectral one.
CLUSTER_LINE = 40
MIN_CLUSTER_SIZE = 4
MERGE_COSINE = 0.8


class SpectralCluster:
    """Spectral clustering; the speaker count is the largest eigengap of the graph Laplacian."""

    def __init__(
        self,
        min_num_spks: int = 1,
        max_num_spks: int = 15,
        pval: float = 0.012,
        min_pnum: int = 6,
    ) -> None:
        self.min_num_spks = min_num_spks
        self.max_num_spks = max_num_spks
        self.pval = pval
        self.min_pnum = min_pnum

    def __call__(self, x: Any) -> Any:
        sim = cosine_similarity(x, x)
        n_elems = min(int((1 - self.pval) * sim.shape[0]), sim.shape[0] - self.min_pnum)
        for i in range(sim.shape[0]):
            sim[i, np.argsort(sim[i, :])[:n_elems]] = 0
        sym = 0.5 * (sim + sim.T)
        affinity = sym.copy()
        np.fill_diagonal(affinity, 0)
        laplacian = -affinity
        laplacian[np.diag_indices_from(laplacian)] = np.sum(np.abs(affinity), axis=1)
        lambdas, vecs = scipy.sparse.linalg.eigsh(
            laplacian, k=min(self.max_num_spks + 1, laplacian.shape[0]), which="SM"
        )
        gaps = lambdas[self.min_num_spks - 1 : self.max_num_spks + 1]
        k = (
            int(np.argmax([float(gaps[i + 1]) - float(gaps[i]) for i in range(len(gaps) - 1)]))
            + self.min_num_spks
        )
        _, labels, _ = k_means(vecs[:, :k], k, n_init=KMEANS_N_INIT, random_state=KMEANS_SEED)
        return labels


def ahc(x: Any, fix_cos_thr: float = 0.4) -> Any:
    """Average-linkage clustering cut at cosine similarity `fix_cos_thr`."""
    distances = np.clip(squareform(1 - cosine_similarity(x), checks=False), 0, None)
    return fcluster(linkage(distances, method="average"), 1 - fix_cos_thr, criterion="distance") - 1


def cluster(x: Any) -> Any:
    """Raw speaker label of each chunk embedding (rows of `x`)."""
    if x.shape[0] <= 1:
        return np.zeros(x.shape[0], dtype=int)
    labels = ahc(x) if x.shape[0] < CLUSTER_LINE else SpectralCluster()(x)

    found = np.unique(labels)
    sizes = np.array([(labels == i).sum() for i in found])
    minor, major = found[sizes <= MIN_CLUSTER_SIZE], found[sizes > MIN_CLUSTER_SIZE]
    if len(minor):
        if not len(major):
            labels = np.zeros_like(labels)
        else:
            centers = np.stack([x[labels == i].mean(0) for i in major])
            for i in range(len(labels)):
                if labels[i] in minor:
                    labels[i] = major[cosine_similarity(x[i][None], centers).argmax()]

    while True:
        found = np.unique(labels)
        if len(found) == 1:
            break
        centers = np.stack([x[labels == i].mean(0) for i in found])
        affinity = np.triu(cosine_similarity(centers, centers), 1)
        best = np.unravel_index(np.argmax(affinity), affinity.shape)
        if affinity[best] < MERGE_COSINE:
            break
        first, second = found[np.array(best)]
        labels[labels == second] = first
    return labels


def compressed_seg(segments: list[list[Any]]) -> list[list[Any]]:
    """Joins adjacent chunks of one speaker; where two speakers overlap, splits the overlap."""
    merged: list[list[Any]] = []
    for index, (start, end, label) in enumerate(segments):
        if index == 0:
            merged.append([start, end, label])
        elif label == merged[-1][2]:
            if start > merged[-1][1]:
                merged.append([start, end, label])
            else:
                merged[-1][1] = end
        else:
            if start < merged[-1][1]:
                middle = (merged[-1][1] + start) / 2
                merged[-1][1] = middle
                start = middle
            merged.append([start, end, label])
    return merged
