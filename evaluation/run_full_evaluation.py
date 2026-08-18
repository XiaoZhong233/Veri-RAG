#!/usr/bin/env python3
"""Run live accommodation evaluation and the semantic-accuracy judge."""
import argparse
import subprocess
import sys
from datetime import datetime
from pathlib import Path


def add_if_present(command, option, value):
    if value:
        command.extend([option, value])


def main():
    parser = argparse.ArgumentParser(
        description="Run live accommodation cases, then calculate semantic Accuracy."
    )
    parser.add_argument("--base-url", default="http://localhost:8081/veri-rag")
    parser.add_argument("--token", help="Existing app JWT; skips login")
    parser.add_argument("--username", help="Evaluation account; otherwise runner reads VERI_RAG_EVAL_USERNAME")
    parser.add_argument("--password", help="Evaluation password; otherwise runner prompts or reads env var")
    parser.add_argument("--gold-set", default="evaluation/gold_set.jsonl")
    parser.add_argument("--output-dir",
                        help="Run directory; defaults to evaluation/output/runs/<timestamp>")
    parser.add_argument("--samples", type=int, default=3)
    parser.add_argument("--timeout", type=int, default=90, help="Per RAG request timeout in seconds")
    parser.add_argument("--judge-api-key", help="Override the project Spring AI API key")
    parser.add_argument("--judge-base-url", help="Override the project Spring AI base URL")
    parser.add_argument("--judge-model", help="Override the project Spring AI chat model")
    parser.add_argument("--judge-timeout", type=int, default=60)
    args = parser.parse_args()

    scripts_dir = Path(__file__).resolve().parent
    output_dir = Path(args.output_dir or
                      f"evaluation/output/runs/{datetime.now().strftime('%Y%m%d-%H%M%S')}")
    evaluate_command = [
        sys.executable, str(scripts_dir / "run_evaluation.py"),
        "--base-url", args.base_url,
        "--gold-set", args.gold_set,
        "--output-dir", str(output_dir),
        "--samples", str(args.samples),
        "--timeout", str(args.timeout),
    ]
    add_if_present(evaluate_command, "--token", args.token)
    add_if_present(evaluate_command, "--username", args.username)
    add_if_present(evaluate_command, "--password", args.password)
    subprocess.run(evaluate_command, check=True)

    accuracy_command = [
        sys.executable, str(scripts_dir / "judge_accuracy.py"),
        "--results", str(output_dir / "results.jsonl"),
        "--gold-set", args.gold_set,
        "--output", str(output_dir / "llm_accuracy.jsonl"),
        "--timeout", str(args.judge_timeout),
    ]
    add_if_present(accuracy_command, "--api-key", args.judge_api_key)
    add_if_present(accuracy_command, "--base-url", args.judge_base_url)
    add_if_present(accuracy_command, "--model", args.judge_model)
    subprocess.run(accuracy_command, check=True)


if __name__ == "__main__":
    main()
