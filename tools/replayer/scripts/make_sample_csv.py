"""Writes a synthetic CSV with the columns of the ULB fraud dataset, for load tests when the real file is absent.

The data is random and labeled synthetic in its name. It is not the real dataset and proves nothing about fraud.
"""

import argparse
import csv
import random


def main() -> None:
    """Generate rows with increasing Time, a log normal Amount, and about 0.17 percent fraud."""
    p = argparse.ArgumentParser()
    p.add_argument("--rows", type=int, default=100_000)
    p.add_argument("--out", default="data/synthetic-ulb-like.csv")
    p.add_argument("--seed", type=int, default=7)
    args = p.parse_args()
    rng = random.Random(args.seed)
    clock = 0.0
    with open(args.out, "w", newline="") as handle:
        writer = csv.writer(handle)
        writer.writerow(["Time", "V1", "Amount", "Class"])
        for _ in range(args.rows):
            clock += rng.expovariate(1 / 0.6)
            writer.writerow(
                [
                    round(clock, 3),
                    round(rng.gauss(0, 1), 4),
                    round(rng.lognormvariate(3.5, 1.2), 2),
                    int(rng.random() < 0.0017),
                ]
            )


if __name__ == "__main__":
    main()
