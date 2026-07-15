import os
import sys
import csv
import re
import time
import shutil
import subprocess
import threading
from flask import Flask, request, jsonify, render_template, Response, send_file

app = Flask(__name__, template_folder='templates')

ROOT = os.path.abspath(os.path.dirname(__file__))
INPUT_DIR = os.path.join(ROOT, 'input')
STATE_DIR = os.path.join(ROOT, 'state')
WORK_DIR = os.path.join(ROOT, 'work')
OUTPUT_DIR = os.path.join(ROOT, 'output')

# Ensure directories exist
for d in [INPUT_DIR, STATE_DIR, WORK_DIR, OUTPUT_DIR]:
    os.makedirs(d, exist_ok=True)

# Subprocess PID-based tracker
pipeline_lock = threading.Lock()

def start_pipeline_process(log_file, mode='w'):
    """local_pipeline.py를 서브프로세스로 띄우고, stdout/stderr를 파이프로 받아
    UTF-8로 디코딩해 로그 파일에 기록하는 백그라운드 스레드를 함께 시작한다.
    Windows에서 stdout=<file> 직접 리다이렉트 방식은 콘솔 코드페이지(cp949) 영향으로
    한글 출력이 씹히거나 프로세스가 조용히 종료되는 문제가 있어 파이프 방식으로 우회한다."""
    env = dict(os.environ, PYTHONIOENCODING="utf-8")
    script_path = os.path.join(ROOT, 'scripts', 'local_pipeline.py')
    # 예전에 CREATE_NEW_PROCESS_GROUP만 단독으로 줬을 때 STATUS_DLL_INIT_FAILED(0xC0000142)로
    # 자식이 즉사했는데, 원인은 플래그 자체가 아니라 stdin을 지정하지 않아 자식이 부모 콘솔의
    # stdin 핸들을 그대로 물려받으려다 초기화에 실패한 것이었다. stdin=DEVNULL로 콘솔 핸들
    # 상속을 끊어주면 CREATE_NEW_PROCESS_GROUP과 함께 써도 안전하다.
    # CREATE_NEW_PROCESS_GROUP이 없으면 자식이 부모와 같은 콘솔 프로세스 그룹에 남아있다가
    # (자신의 Ctrl+C 핸들러를 등록하기도 전에) STATUS_CONTROL_C_EXIT(0xC000013A)로 즉시
    # 종료되는 문제가 있었다 — 이게 "분석 시작을 누르면 바로 꺼지는" 버그의 원인이었다.
    popen_kwargs = {}
    if os.name == 'nt':
        popen_kwargs['creationflags'] = subprocess.CREATE_NEW_PROCESS_GROUP
    try:
        proc = subprocess.Popen(
            [sys.executable, '-u', script_path],
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            cwd=ROOT,
            env=env,
            encoding='utf-8',
            errors='replace',
            **popen_kwargs,
        )
    except Exception as e:
        with open(log_file, mode, encoding='utf-8') as log_f:
            log_f.write(f"[System] Popen 실행 자체가 실패했다: {e!r}\n")
        raise

    def _pump():
        try:
            with open(log_file, mode, encoding='utf-8') as log_f:
                log_f.write(f"[System] subprocess 시작: pid={proc.pid} exe={sys.executable} script={script_path}\n")
                log_f.flush()
                for line in proc.stdout:
                    log_f.write(line)
                    log_f.flush()
                ret = proc.wait()
                log_f.write(f"[System] subprocess 종료: returncode={ret}\n")
                log_f.flush()
        except Exception as e:
            with open(log_file, 'a', encoding='utf-8') as log_f:
                log_f.write(f"[System] 로그 펌프 스레드 예외: {e!r}\n")

    threading.Thread(target=_pump, daemon=True).start()
    return proc

def is_pid_running(pid):
    if pid <= 0:
        return False
    try:
        # os.kill(pid, 0) checks process existence on both Unix and Windows Python 3
        os.kill(pid, 0)
        return True
    except OSError:
        return False

def get_saved_pid():
    pid_file = os.path.join(STATE_DIR, 'pipeline.pid')
    if os.path.exists(pid_file):
        try:
            with open(pid_file, 'r') as f:
                return int(f.read().strip())
        except:
            pass
    return None

def save_pid(pid):
    pid_file = os.path.join(STATE_DIR, 'pipeline.pid')
    try:
        with open(pid_file, 'w') as f:
            f.write(str(pid))
    except Exception as e:
        print(f"Failed to save PID: {e}", file=sys.stderr)

def delete_pid_file():
    pid_file = os.path.join(STATE_DIR, 'pipeline.pid')
    if os.path.exists(pid_file):
        try:
            os.unlink(pid_file)
        except:
            pass

def is_pipeline_running():
    pid = get_saved_pid()
    if pid is None:
        return False
    
    if is_pid_running(pid):
        return True
    else:
        delete_pid_file()
        return False

def clear_directory(directory_path, keep_extensions=None):
    if not os.path.exists(directory_path):
        return
    for filename in os.listdir(directory_path):
        file_path = os.path.join(directory_path, filename)
        try:
            if os.path.isfile(file_path) or os.path.islink(file_path):
                if keep_extensions and any(filename.endswith(ext) for ext in keep_extensions):
                    continue
                os.unlink(file_path)
            elif os.path.isdir(file_path):
                shutil.rmtree(file_path)
        except Exception as e:
            print(f"Failed to delete {file_path}. Reason: {e}")

def parse_progress():
    progress_path = os.path.join(STATE_DIR, 'PROGRESS.md')
    if not os.path.exists(progress_path):
        return None
    
    phases = {
        "phase0": [],
        "phase1": [],
        "phase2": []
    }
    
    current_phase = None
    try:
        with open(progress_path, 'r', encoding='utf-8') as f:
            for line in f:
                line_str = line.strip()
                if "Phase 0" in line_str:
                    current_phase = "phase0"
                    continue
                elif "Phase 1" in line_str:
                    current_phase = "phase1"
                    continue
                elif "Phase 2" in line_str:
                    current_phase = "phase2"
                    continue
                
                if current_phase and line_str.startswith("- "):
                    # Match standard markdown checkboxes: - [x] or - [ ] or - [!]
                    match = re.match(r"^-\s+\[(.*?)\]\s+(.*)$", line_str)
                    if match:
                        chk = match.group(1).strip()
                        txt = match.group(2).strip()
                        
                        status = "pending"
                        if chk.lower() == 'x':
                            status = "done"
                        elif '!' in chk or 'blocked' in txt.lower() or 'blocked' in chk.lower():
                            status = "blocked"
                        
                        phases[current_phase].append({
                            "text": txt,
                            "status": status
                        })
                        
        # Mark the active task as 'running' if pipeline is currently active
        if is_pipeline_running():
            found_running = False
            for p in ["phase0", "phase1", "phase2"]:
                for item in phases[p]:
                    if item["status"] == "pending":
                        item["status"] = "running"
                        found_running = True
                        break
                if found_running:
                    break
                    
        return phases
    except Exception as e:
        print(f"Error parsing progress: {e}", file=sys.stderr)
        return None

def get_output_files():
    csv_file = None
    md_file = None
    coverage_file = os.path.join(STATE_DIR, 'coverage_report.md')
    
    if os.path.exists(OUTPUT_DIR):
        for filename in os.listdir(OUTPUT_DIR):
            full_path = os.path.join(OUTPUT_DIR, filename)
            if os.path.isfile(full_path):
                if filename.endswith('.csv') and filename.startswith('TC_'):
                    csv_file = full_path
                elif filename.endswith('.md') and filename.startswith('의문점_'):
                    md_file = full_path
                    
        # Fallback to any csv/md in output if prefix-based match failed
        if not csv_file or not md_file:
            for filename in os.listdir(OUTPUT_DIR):
                full_path = os.path.join(OUTPUT_DIR, filename)
                if os.path.isfile(full_path):
                    if filename.endswith('.csv') and not csv_file:
                        csv_file = full_path
                    elif filename.endswith('.md') and not md_file:
                        md_file = full_path
                        
    return csv_file, md_file, coverage_file if os.path.exists(coverage_file) else None

@app.route('/')
def index():
    return render_template('index.html')

@app.route('/api/status', methods=['GET'])
def get_status():
    running = is_pipeline_running()
    needs_human_file = os.path.join(STATE_DIR, 'NEEDS_HUMAN')
    done_file = os.path.join(STATE_DIR, 'DONE')
    
    status = "IDLE"
    message = ""
    
    if running:
        status = "RUNNING"
    elif os.path.exists(needs_human_file):
        status = "NEEDS_HUMAN"
        try:
            with open(needs_human_file, 'r', encoding='utf-8') as f:
                message = f.read().strip()
        except:
            message = "Human intervention required."
    elif os.path.exists(done_file):
        status = "COMPLETED"
        try:
            with open(done_file, 'r', encoding='utf-8') as f:
                message = f.read().strip()
        except:
            message = "Analysis finished successfully."
    elif os.path.exists(os.path.join(STATE_DIR, 'PROGRESS.md')):
        # Process ended but not completed and no needs_human means it failed/stopped
        status = "STOPPED"
        message = "Pipeline stopped or failed. Check the log output."
        
    progress = parse_progress()
    
    return jsonify({
        "status": status,
        "message": message,
        "progress": progress
    })

def safe_upload_filename(filename):
    """경로 구분자·상위 디렉토리 이동만 제거하고 한글 등 유니코드 파일명은 그대로 보존한다.
    (werkzeug의 secure_filename()은 비-ASCII 문자를 전부 삭제해 한글 파일명이 깨진다.)"""
    name = os.path.basename(filename.replace('\\', '/').replace('..', ''))
    return name or 'upload'

def clear_stale_input_files(keep_filenames, keep_reference=False):
    """input/ 안에서 이번 업로드로 새로 저장될 파일(keep_filenames)이 아닌 것만 지운다.
    같은 이름으로 재업로드할 때 save()가 어차피 덮어쓰므로 미리 지울 필요가 없고,
    다른 프로그램이 파일을 열어둬서 삭제가 실패해도(WinError 32) 전체 흐름에 지장이 없다."""
    if not os.path.exists(INPUT_DIR):
        return
    for filename in os.listdir(INPUT_DIR):
        if filename in keep_filenames:
            continue
        if keep_reference and (filename.endswith('.csv') or filename.endswith('.xlsx')):
            continue
        file_path = os.path.join(INPUT_DIR, filename)
        try:
            if os.path.isfile(file_path):
                os.unlink(file_path)
        except Exception as e:
            print(f"Failed to delete {file_path}. Reason: {e}")

@app.route('/api/upload', methods=['POST'])
def upload_files():
    if is_pipeline_running():
        return jsonify({"error": "Pipeline is already running"}), 400

    pdf_file = request.files.get('pdf')
    csv_style_file = request.files.get('csv')

    if not pdf_file or not pdf_file.filename:
        return jsonify({"error": "PDF file is required"}), 400

    pdf_name = safe_upload_filename(pdf_file.filename)
    if not pdf_name.lower().endswith('.pdf'):
        return jsonify({"error": "기획서 파일은 .pdf 확장자여야 한다."}), 400

    csv_name = None
    if csv_style_file and csv_style_file.filename:
        csv_name = safe_upload_filename(csv_style_file.filename)
        if not (csv_name.lower().endswith('.csv') or csv_name.lower().endswith('.xlsx')):
            return jsonify({"error": "스타일 가이드 파일은 .csv 또는 .xlsx 확장자여야 한다."}), 400

    # Step 1: 이번 실행에서 새로 만들 상태/작업물을 위해 초기화
    clear_directory(STATE_DIR)
    clear_directory(WORK_DIR)
    clear_directory(OUTPUT_DIR)
    clear_stale_input_files(
        keep_filenames={pdf_name} | ({csv_name} if csv_name else set()),
        keep_reference=not csv_name,
    )

    # Reset log file
    log_file = os.path.join(STATE_DIR, 'run.log')
    if os.path.exists(log_file):
        os.unlink(log_file)

    # Save PDF
    pdf_path = os.path.join(INPUT_DIR, pdf_name)
    pdf_file.save(pdf_path)

    # Save CSV reference (optional, if provided)
    if csv_name:
        csv_style_file.save(os.path.join(INPUT_DIR, csv_name))
    else:
        # Check if we have at least one CSV/XLSX file in input directory to serve as reference
        existing_tc = [f for f in os.listdir(INPUT_DIR) if f.endswith('.csv') or f.endswith('.xlsx')]
        if not existing_tc:
            return jsonify({"error": "Reference CSV/style guide is missing in the workspace input directory. Please upload one."}), 400

    # Step 2: Start local_pipeline.py as a background subprocess (with -u for unbuffered logs)
    with pipeline_lock:
        proc = start_pipeline_process(log_file, mode='w')
        save_pid(proc.pid)

    return jsonify({"status": "RUNNING", "message": "Pipeline started successfully."})

@app.route('/api/stop', methods=['POST'])
def stop_pipeline():
    with pipeline_lock:
        pid = get_saved_pid()
        if pid and is_pid_running(pid):
            # Windows taskkill is highly reliable and kills child processes (-t)
            try:
                subprocess.run(['taskkill', '/F', '/T', '/PID', str(pid)], capture_output=True)
            except Exception as e:
                # Fallback to direct kill
                try:
                    os.kill(pid, 9)
                except:
                    pass
            delete_pid_file()
            
            # Write termination message to logs
            log_file = os.path.join(STATE_DIR, 'run.log')
            if os.path.exists(log_file):
                with open(log_file, 'a', encoding='utf-8') as f:
                    f.write("\n[System] Pipeline terminated by user.\n")
                    
            return jsonify({"status": "STOPPED", "message": "Pipeline stopped successfully."})
        return jsonify({"status": "IDLE", "message": "Pipeline is not running."})

@app.route('/api/resume', methods=['POST'])
def resume_pipeline():
    if is_pipeline_running():
        return jsonify({"error": "Pipeline is already running"}), 400
        
    # Delete NEEDS_HUMAN file
    needs_human_file = os.path.join(STATE_DIR, 'NEEDS_HUMAN')
    if os.path.exists(needs_human_file):
        os.unlink(needs_human_file)
        
    # Start the pipeline again (it will resume based on PROGRESS.md state)
    log_file = os.path.join(STATE_DIR, 'run.log')
    with pipeline_lock:
        with open(log_file, 'a', encoding='utf-8') as f:
            f.write("\n--- Resuming pipeline execution ---\n")
        proc = start_pipeline_process(log_file, mode='a')
        save_pid(proc.pid)

    return jsonify({"status": "RUNNING", "message": "Pipeline resumed successfully."})

@app.route('/api/logs', methods=['GET'])
def get_logs():
    def generate():
        log_file = os.path.join(STATE_DIR, 'run.log')
        if not os.path.exists(log_file):
            # Wait a moment for file creation
            time.sleep(1)
            if not os.path.exists(log_file):
                yield "data: [Web Server] No log file created yet...\n\n"
                return
                
        with open(log_file, 'r', encoding='utf-8', errors='replace') as f:
            # SSE는 각 이벤트가 빈 줄(\n\n)로 끝나야 브라우저의 EventSource가 메시지를
            # 실제로 dispatch한다. line은 파일에서 읽은 한 줄이라 이미 \n으로 끝나 있으므로,
            # 그 뒤에 \n을 하나 더 붙여 "data: ...\n\n" 형태로 만들어야 한다. 이게 빠져 있으면
            # 서버는 로그를 정상적으로 쌓아도 브라우저 콘솔에는 아무것도 안 찍힌다.
            # Yield existing contents
            while True:
                line = f.readline()
                if not line:
                    break
                yield f"data: {line}\n"

            # Yield new lines as they arrive
            while True:
                line = f.readline()
                if not line:
                    if not is_pipeline_running():
                        # Double check for final output
                        line = f.readline()
                        if line:
                            yield f"data: {line}\n"
                        break
                    time.sleep(0.3)
                    continue
                yield f"data: {line}\n"
                
    return Response(generate(), mimetype='text/event-stream')

@app.route('/api/outputs', methods=['GET'])
def get_outputs():
    csv_path, md_path, coverage_path = get_output_files()
    
    response = {
        "csv": None,
        "markdown": None,
        "coverage": None,
        "csv_filename": None,
        "markdown_filename": None
    }
    
    if csv_path and os.path.exists(csv_path):
        response["csv_filename"] = os.path.basename(csv_path)
        try:
            with open(csv_path, 'r', encoding='utf-8-sig', errors='replace') as f:
                reader = csv.DictReader(f)
                response["csv"] = list(reader)
        except Exception as e:
            response["csv"] = {"error": f"Error parsing CSV: {e}"}
            
    if md_path and os.path.exists(md_path):
        response["markdown_filename"] = os.path.basename(md_path)
        try:
            with open(md_path, 'r', encoding='utf-8', errors='replace') as f:
                response["markdown"] = f.read()
        except Exception as e:
            response["markdown"] = f"Error reading markdown file: {e}"
            
    if coverage_path and os.path.exists(coverage_path):
        try:
            with open(coverage_path, 'r', encoding='utf-8', errors='replace') as f:
                response["coverage"] = f.read()
        except Exception as e:
            response["coverage"] = f"Error reading coverage file: {e}"
            
    return jsonify(response)

@app.route('/api/download/<file_type>', methods=['GET'])
def download_file(file_type):
    csv_path, md_path, _ = get_output_files()
    
    if file_type == 'csv' and csv_path and os.path.exists(csv_path):
        return send_file(csv_path, as_attachment=True, download_name=os.path.basename(csv_path))
    elif file_type == 'markdown' and md_path and os.path.exists(md_path):
        return send_file(md_path, as_attachment=True, download_name=os.path.basename(md_path))
    else:
        return jsonify({"error": "File not found"}), 404

if __name__ == '__main__':
    # Listen on port 5000 by default
    # use_reloader=False: 리로더가 파일 변경을 감지해 앱을 재시작할 때, 백그라운드로 띄운
    # local_pipeline.py 자식 프로세스와 로그 펌프 스레드가 함께 정리되어 버려서 끔.
    # threaded=True: /api/logs가 SSE로 연결을 계속 물고 있는 동안에도(파이프라인 끝날 때까지)
    # 다른 요청(/api/upload, /api/status 등)을 동시에 처리하려면 필요하다 — 없으면 개발서버가
    # 싱글스레드라 SSE 연결 하나가 서버 전체를 막아버린다.
    app.run(host='0.0.0.0', port=5000, debug=True, use_reloader=False, threaded=True)
