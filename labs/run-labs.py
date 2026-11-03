#!/usr/bin/env python3
"""
Run the handbook's labs the way their READMEs say, and check they still produce the results the chapters quote.

    labs/run-labs.py                        run every automatable lab whose needs are available
    labs/run-labs.py --tier jdk             only labs that need nothing but JDK 21
    labs/run-labs.py --tier postgres,redis  labs that need the docker-compose services (labs/docker-compose.yml)
    labs/run-labs.py --only ch45            labs whose id starts with ch45
    labs/run-labs.py --shard 2/4            the 2nd quarter of the selection (CI runs shards in parallel)
    labs/run-labs.py --list                 show the manifest
    labs/run-labs.py --record               (maintainers) recompute each lab's expected lines from this run

How a lab is checked (manifest: labs/labs.json):
  1. it must exit with status 0 within its timeout;
  2. every line in "expect" must appear in its output. Those lines were taken from the recorded output the chapter quotes
     (`out`) and confirmed identical on independent re-runs, so they are the deterministic results (seeded simulations,
     counts, verdicts). Timings and other run-to-run numbers are deliberately not checked.
Labs marked "manual" need a chapter-specific setup script or external tool (see each README); they are listed, not run.

Needs: jdk (JDK 21 = `java` on PATH) · jdk25 (JDK 25 at $JAVA25_HOME) · postgres (PostgreSQL 16 on :5581, db shop,
user claude, trust auth) · redis (Redis 7 on :6390) · redis-server (the binary, for labs whose README starts an extra
instance; see "pre"/"post") · kafka-jar · child-jvm. Jars are downloaded to labs/.cache/.
"""
import argparse, json, os, re, shutil, socket, subprocess, sys, time, urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
MANIFEST = os.path.join(HERE, 'labs.json')
CACHE = os.path.join(HERE, '.cache')
JARS = {
    'postgresql-42.7.4.jar': 'https://repo1.maven.org/maven2/org/postgresql/postgresql/42.7.4/postgresql-42.7.4.jar',
    'kafka-clients-3.9.0.jar': 'https://repo1.maven.org/maven2/org/apache/kafka/kafka-clients/3.9.0/kafka-clients-3.9.0.jar',
    'slf4j-api-1.7.36.jar': 'https://repo1.maven.org/maven2/org/slf4j/slf4j-api/1.7.36/slf4j-api-1.7.36.jar',
}
# jar names the READMEs use for the same artefact
JAR_ALIASES = {'pgjdbc.jar': 'postgresql-42.7.4.jar', '/tmp/pgjdbc.jar': 'postgresql-42.7.4.jar',
               'postgresql-42.7.x.jar': 'postgresql-42.7.4.jar'}


def jar(name):
    os.makedirs(CACHE, exist_ok=True)
    path = os.path.join(CACHE, name)
    if not os.path.exists(path):
        print(f'  downloading {name}', flush=True)
        for attempt in range(6):                       # Maven Central rate-limits bursts (HTTP 429): back off and retry
            try:
                urllib.request.urlretrieve(JARS[name], path + '.part')
                break
            except OSError as e:
                if attempt == 5: raise
                print(f'  retry in {5 * 2 ** attempt}s ({e})', flush=True)
                time.sleep(5 * 2 ** attempt)
        os.replace(path + '.part', path)
    return path


def port_open(port):
    with socket.socket() as s:
        s.settimeout(1)
        return s.connect_ex(('127.0.0.1', port)) == 0


def available_needs():
    have = {'jdk', 'child-jvm', 'kafka-jar'}
    if shutil.which('redis-server'): have.add('redis-server')        # labs that start their own extra Redis (pre/post)
    if os.environ.get('JAVA25_HOME') and os.path.exists(os.path.join(os.environ['JAVA25_HOME'], 'bin', 'java')):
        have.add('jdk25')
    if port_open(5581): have.add('postgres')
    if port_open(6390): have.add('redis')
    return have


def tier_of(lab):
    n = set(lab['needs']) - {'child-jvm', 'kafka-jar', 'redis-server'}
    return ','.join(sorted(n)) or 'jdk'


def build_command(lab):
    cmd = lab['cmd']
    for alias, real in JAR_ALIASES.items():
        cmd = re.sub(r'(?<![\w/.-])' + re.escape(alias) + r'(?![\w.-])', real, cmd)
    for name in JARS:
        if name in cmd:
            cmd = re.sub(r'(?<![\w/.-])' + re.escape(name), jar(name), cmd)
    if 'jdk25' in lab['needs']:
        java25 = os.path.join(os.environ['JAVA25_HOME'], 'bin', 'java')
        cmd = re.sub(r'^(/opt/jdk25/bin/java|java)(?= )', java25, cmd)
        cmd = cmd.replace('&& java ', f'&& {java25} ')
    if '-Dstdout.encoding' not in cmd:
        cmd = re.sub(r'^(\S*java)(?= )', r'\1 -Dstdout.encoding=UTF-8', cmd)
    return cmd


def run(lab):
    cwd = ROOT if lab.get('cwd') == 'root' else os.path.join(HERE, lab['dir'])
    cmd = build_command(lab)
    if lab.get('pre'):                                   # e.g. start the extra redis-server the README asks for
        subprocess.run(['bash', '-c', lab['pre']], cwd=cwd, capture_output=True, timeout=60)
    before = snapshot(cwd) if cwd != ROOT else None
    try:
        return _run(lab, cmd, cwd)
    finally:
        if lab.get('post'):
            subprocess.run(['bash', '-c', lab['post']], cwd=cwd, capture_output=True, timeout=60)
        if before is not None:                           # remove what the lab generated (logs, JFR files, out/, repos/...)
            for path in sorted(snapshot(cwd) - before, key=len, reverse=True):
                full = os.path.join(cwd, path)
                if os.path.isdir(full) and not os.path.islink(full): shutil.rmtree(full, ignore_errors=True)
                elif os.path.lexists(full): os.remove(full)


def snapshot(d):
    out = set()
    for root, dirs, files in os.walk(d):
        for name in dirs + files:
            out.add(os.path.relpath(os.path.join(root, name), d))
    return out


def _run(lab, cmd, cwd):
    start = time.time()
    try:
        p = subprocess.run(['bash', '-c', cmd], cwd=cwd, capture_output=True, text=True, timeout=lab.get('timeout', 600),
                           env={**os.environ, 'JAVA_TOOL_OPTIONS': ''})
        out, code = p.stdout + p.stderr, p.returncode
    except subprocess.TimeoutExpired as e:
        out, code = (e.stdout or b'').decode(errors='replace') if isinstance(e.stdout, bytes) else (e.stdout or ''), 'timeout'
    return code, out, time.time() - start


def norm(line):
    return re.sub(r'\s+', ' ', line).strip()


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--tier', help='comma-separated tiers: jdk, jdk25, postgres, redis, postgres+redis (default: all available)')
    ap.add_argument('--only', help='run labs whose id starts with this prefix (comma-separated)')
    ap.add_argument('--shard', help='i/n: run the i-th of n slices')
    ap.add_argument('--list', action='store_true')
    ap.add_argument('--record', action='store_true', help='recompute expected lines (maintainers)')
    ap.add_argument('--report', help='write a JSON report here')
    a = ap.parse_args()

    labs = json.load(open(MANIFEST))
    if a.list:
        for l in labs:
            state = 'manual: ' + l['manual'] if 'manual' in l else f"{tier_of(l):15} expect {len(l.get('expect', [])):2} lines"
            print(f"{l['id']:28} {state}")
        return 0

    have = available_needs()
    sel = [l for l in labs if 'manual' not in l and not l.get('setup')]
    if a.tier:
        tiers = {t.strip().replace('+', ',') for t in a.tier.split(',') if t.strip()}
        # "postgres,redis" passed as one argument means the combined tier; otherwise each name is a tier
        sel = [l for l in sel if tier_of(l) in tiers or (len(tiers) > 1 and set(tier_of(l).split(',')) <= tiers)]
    if a.only:
        prefixes = tuple(p.strip() for p in a.only.split(','))
        sel = [l for l in sel if l['id'].startswith(prefixes)]
    skipped = [l for l in sel if not set(l['needs']) <= have]
    sel = [l for l in sel if set(l['needs']) <= have]
    if a.shard:
        i, n = map(int, a.shard.split('/'))
        sel = sel[i - 1::n]

    print(f"{len(sel)} labs to run" + (f", {len(skipped)} skipped (needs not available: "
          f"{sorted(set(x for l in skipped for x in l['needs']) - have)})" if skipped else ''), flush=True)
    results, failed = [], 0
    for l in sel:
        # setup steps a lab depends on (e.g. ch22's generated catalogue) run first
        for dep in l.get('after', []):
            run(next(x for x in labs if x['id'] == dep))
        code, out, secs = run(l)
        got = {norm(x) for x in out.splitlines()}
        expect = l.get('expect', [])
        missing = [e for e in expect if norm(e) not in got]
        if a.record and code == 0 and not l.get('out'):
            # README didn't name the output file: use the recorded *.txt that shares the most lines with this run
            best, score = None, 0
            for f in sorted(os.listdir(os.path.join(HERE, l['dir']))):
                if f.endswith('.txt'):
                    lines = {norm(x) for x in open(os.path.join(HERE, l['dir'], f), encoding='utf-8', errors='replace')}
                    n = len({x for x in lines if x} & got)
                    if n > score: best, score = f, n
            if score >= 3: l['out'] = best
        if a.record and code == 0:
            rec = os.path.join(HERE, l['dir'], l['out']) if l.get('out') else None
            recorded = [norm(x) for x in open(rec, encoding='utf-8')] if rec and os.path.exists(rec) else []
            prev = l.get('candidates')
            cand = [x for x in recorded if x and x in got]
            if prev is not None: cand = [x for x in cand if x in prev]       # must survive every recording run
            l['candidates'] = cand
            keep = [x for x in cand if re.search(r'\d', x)] or cand
            if len(keep) > 12:                                   # spread the checks over the whole output, not just the header
                keep = [keep[round(i * (len(keep) - 1) / 11)] for i in range(12)]
            l['expect'] = keep
            missing = []
        ok = code == 0 and not missing
        failed += not ok
        results.append({'id': l['id'], 'ok': ok, 'code': code, 'seconds': round(secs, 1), 'checked': len(expect),
                        'missing': missing[:3]})
        mark = 'PASS' if ok else 'FAIL'
        detail = f"{len(expect)} result lines matched" if ok and expect else ('exit 0 (smoke: no stable lines)' if ok else
                  f"exit {code}" + (f"; missing: {missing[0][:90]!r}" if missing else '') + ('\n      ' + '\n      '.join(out.strip().splitlines()[-4:]) if code != 0 else ''))
        print(f"{mark}  {l['id']:28} {secs:6.1f}s  {detail}", flush=True)

    if a.record:
        json.dump(labs, open(MANIFEST, 'w'), indent=1, ensure_ascii=False)
    if a.report:
        json.dump(results, open(a.report, 'w'), indent=1)
    print(f"\n{len(results) - failed}/{len(results)} labs passed" + (f"; {len(skipped)} skipped" if skipped else ''))
    return 1 if failed else 0


if __name__ == '__main__':
    sys.exit(main())
