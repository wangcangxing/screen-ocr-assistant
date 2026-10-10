# omniparser-service

本地 OmniParser 解析服务（PC 侧 Python），实现冻结契约
[`docs/接口约定-SoM与解析服务.md`](../docs/接口约定-SoM与解析服务.md) 第 1 节：

| 端点 | 说明 |
|---|---|
| `GET /health` | 服务状态（mode / backend / device / caption_enabled / weights / uptime_s） |
| `POST /parse` | 截图 base64 → 结构化元素（可选返回自己编号的标注图） |

只用标准库 `http.server` + `threading` 起服务；推理用一把 `threading.Lock` 串行化。
默认监听 `0.0.0.0:8010`。

## 1. 安装

```powershell
cd D:\program\screen-ocr-assistant-omniparser\omniparser-service

# 1) 建 venv（用显式路径的 Python 3.12；PATH 上的 python 是 msys64 的，没有 pip）
D:\Python312\python.exe -m venv --system-site-packages .venv

# 2) 装依赖（国内源；实测可达）
.\.venv\Scripts\python.exe -m pip install -r requirements.txt -i https://pypi.tuna.tsinghua.edu.cn/simple
```

`--system-site-packages` 是为了复用 `D:\Python312` 里已装好的 `torch 2.9.1+cpu`（CPU 版，无 CUDA）。
若要从零装，去掉该参数即可，`requirements.txt` 里已 pin 全部版本。venv 里装的包会正确遮蔽全局同名包
（实测：全局是 `transformers 5.3.0`，venv 内 `import transformers` 得到 `4.46.1`）。
**实际装成的版本**：

```
torch 2.9.1+cpu, torchvision 0.24.1+cpu, transformers 4.46.1, tokenizers 0.20.3,
timm 1.0.30, einops 0.8.2, easyocr 1.7.2, opencv-python-headless 5.0.0.93,
scikit-image 0.26.0, python-bidi 0.6.11, pyclipper 1.4.0, Shapely 2.2.0,
huggingface-hub 0.36.2, numpy 2.4.1, Pillow 12.1.0, scipy 1.17.0
```

> **`transformers` 必须 pin 在 4.46.x**（装的 4.46.1）。`icon_caption_florence/config.json` 里记录的
> 就是 `transformers_version: 4.46.1`，用它走远程代码路径加载权重是 **0 missing / 0 unexpected keys**；
> 换成 `transformers 5.x`（全局那份）或 `4.57.1` 都装不起来，实测报错与结论见 §5「已知限制」。
> pip 源注意：`pypi.tuna.tsinghua.edu.cn` 中途出现过 SSL 证书过期（`CERTIFICATE_VERIFY_FAILED`），
> 当时改用 `-i https://mirrors.aliyun.com/pypi/simple/` 装上。

## 2. 下载权重（约 1.4 GB）

```powershell
powershell -ExecutionPolicy Bypass -File .\download_weights.ps1
# 或指定官方站： -Endpoint https://huggingface.co
```

落地布局（`server.py` 只认这一种）：

```
weights\
  icon_detect_v3\model.pt                      281 MB  YOLOv9 图标检测（revision refs/pr/37）
  icon_caption_florence\config.json            5.7 KB
  icon_caption_florence\generation_config.json 292 B
  icon_caption_florence\model.safetensors      ~0.99 GB Florence-2 图标描述
  florence2_base_processor\...                 ~ 小文件，microsoft/Florence-2-base 的 processor
```

**已核实的两个坑（实测 2026-10-10）**：

1. 上游仓库里**没有** `icon_caption_florence` 目录，只有 `icon_caption/`；
   `icon_caption_florence` 只是本地的目标目录名，下载脚本负责改名。
2. `https://hf-mirror.com/<repo>/resolve/...` 现在返回 **HTTP 308 跳转到 huggingface.co**；
   `huggingface_hub` 的元数据探测用 `allow_redirects=False`，于是报
   `FileMetadataError: Distant resource does not seem to be on huggingface.co`。
   所以 `download_weights.ps1` **先用镜像、失败自动回落官方站**（官方站实测直连可用、CDN 200）。
3. `HF_ENDPOINT` 必须在 `import huggingface_hub` **之前**设置（`constants.ENDPOINT` 在 import 时固化，
   事后改模块属性无效），因此 `download_weights.py` 一次只处理一个端点，由 `.ps1` 负责重试。

## 3. 启动与验证

```powershell
# mock：不需要任何权重/依赖，用于链路自测（元素 label 一律以 mock- 开头，绝不冒充真实结果）
.\.venv\Scripts\python.exe server.py --mock

# real（lite 后端，CPU，全量 caption；实测单张 ~2.5 分钟）
.\.venv\Scripts\python.exe server.py --backend lite --weights .\weights --device cpu --port 8010 --box-threshold 0.05 --caption-batch 16

# real（lite 后端，CPU，--no-caption：契约允许的合法模式，实测单张 ~15 秒）
.\.venv\Scripts\python.exe server.py --backend lite --weights .\weights --device cpu --port 8011 --box-threshold 0.05 --no-caption
```

启动参数（全部有默认值）：

```
--host 0.0.0.0      --port 8010        --backend lite|upstream
--mock              --weights ./weights
--device auto|cpu|cuda                 --box-threshold 0.05
--caption-batch 32  --no-caption       --max-image-mb 12
```

real 模式缺权重时**直接以退出码 2 退出**，并打印可复制的下载命令（不会"起来但装死"）。

契约自检（真实 HTTP，逐字段校验，返回非 0 即违反契约）：

```powershell
# 全字段校验 + 标注图尺寸校验 + 400/413/404 错误路径
.\.venv\Scripts\python.exe verify_api.py --image ..\shots\q2.png --annotate --check-errors --out run\verify.json

# 另一张真机截图（quiz.png），只看 /parse 与耗时
.\.venv\Scripts\python.exe verify_api.py --image ..\shots\quiz.png --annotate --out run\verify-quiz.json

# 不发标注图（annotate 缺省 false），并确认响应里没有 som_image_base64
.\.venv\Scripts\python.exe verify_api.py --image ..\shots\q2.png --no-annotate
```

`verify_api.py` 校验的项：`/health` 全部键与类型；`image` 尺寸 == 提交 PNG 尺寸；`elements[].id == 1..N`；
先文字后图标；每组按"上→下、左→右（30px 分桶）"；`bbox_ratio ∈ [0,1]` 且 `x1<x2,y1<y2`；
`bbox_px == round(ratio*size)`；`type/source/text/interactable` 的类型与互斥关系；
`annotate=true` 时 `som_image_base64` 解码尺寸 == 提交尺寸；`400/413/404` 错误体为 `{"ok":false,"error":...}`。

手工调用：

```powershell
$b64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes("..\shots\q2.png"))
$body = @{ image_base64 = $b64; annotate = $true; max_elements = 200 } | ConvertTo-Json -Compress
Invoke-RestMethod -Uri http://127.0.0.1:8010/parse -Method Post -ContentType 'application/json' -Body $body |
    ConvertTo-Json -Depth 6
```

### 手机侧联调

* 同一局域网：`http://<PC 的 IP>:8010`（防火墙放行 8010）。
* 或 `adb reverse tcp:8010 tcp:8010` 后用 `http://127.0.0.1:8010`。

## 4. 实现说明（lite 后端）

| 步骤 | 实现 | 出处 |
|---|---|---|
| 图标检测 | `util/yolov9.py::YOLOv9Detector`（TorchScript），`predict(source, conf=box_threshold, imgsz=640, iou=0.7, max_det=300)`，返回**像素** `xyxy` | 上游 vendored 文件，未改动 |
| 文字框 | `easyocr.Reader(['en'], gpu=False).readtext(img, text_threshold=0.8)` | `util/utils.py::check_ocr_box` 的 `easyocr_args={'text_threshold': 0.8}` |
| 图标语义 | crop → 64×64 → `"<CAPTION>"` → 批量 `model.generate(max_new_tokens=20, num_beams=1, do_sample=False)` | 抄 `util/utils.py::get_parsed_content_icon`（唯一差异：crop 用 `PIL.Image.resize` 而非 `cv2.resize`） |
| Florence-2 加载 | processor 来自本地 `weights/florence2_base_processor`（缺则回落到 `microsoft/Florence-2-base` 的 hub 缓存）；模型来自本地 `weights/icon_caption_florence` | 抄 `util/utils.py::get_caption_model_processor` 的 florence2 分支 |

**为什么不 import `util.utils`**：该模块 import 时就建 `easyocr.Reader` 与 `PaddleOCR`，还依赖
`openai/matplotlib/supervision/cv2`。lite 后端因此只复用 `util/yolov9.py`。

**Florence-2 的加载路径（实测结论，不是推断）**：`icon_caption_florence/config.json` 的
`auto_map.AutoModelForCausalLM = microsoft/Florence-2-base-ft--modeling_florence2...` 指向
`microsoft/Florence-2-base-ft` 的远程代码，**这条路才是能用的那条**：

| 组合 | 结果（实测） |
|---|---|
| `transformers==4.46.1` + `trust_remote_code=True`（上游路径） | ✅ **0 missing / 0 unexpected keys**，可出真实 caption |
| `transformers==4.57.1/5.x` + `trust_remote_code=True` | ❌ `AttributeError: 'Florence2ForConditionalGeneration' object has no attribute '_supports_sdpa'` |
| `transformers==4.57.1` + 原生实现（`trust_remote_code=False`） | ❌ 参数名几乎全改：668 missing / 667 unexpected keys（`text_config.model_type='florence2_language'` 在 `CONFIG_MAPPING` 里也没有） |

所以服务按「存在 `auto_map` 就先走 `trust_remote_code=True`、失败再试原生」的顺序加载，
日志里会打印实际生效的路径与版本。远程代码是几个很小的 `.py`（首次由 huggingface_hub 下载并缓存），
权重文件本身已在本地。

**融合与编号**：先全部文字、再全部图标；组内按 **`centerY` 30px 分桶、再按 `centerX`** 排序（与 Android 侧
§2.1 同一套行内判定；注意分桶必须用像素，`_row_sort_key()` 收的是归一化框 + 图片高度）；编号 `1..N` 与
`som_image_base64` 上画的编号一致。YOLO 图标恒 `interactable=true`；文字元素面积占比 ≤60% 视为可交互
（拿不准给 `true`）。图标若与任一文字框 IoU > 0.6 判为误检丢弃（YOLO 常在文字上误触发）。

**mock**：`--mock` 不加载任何模型、不需要权重与 torch，返回 4 个明显假的元素（label 前缀 `mock-`），
仍然遵守全部字段/编号/排序约定，并支持 `annotate`，专供链路自测。

**upstream 后端**：`--backend upstream` 原样 `from util.omniparser import Omniparser`，需要上游全量依赖
（paddleocr 等，见 `third_party/OmniParser-requirements.txt`），仅作对照保留；本 README 的验证不覆盖它。

## 5. 实测性能与已知限制

见本文件末尾"实测记录"一节。

已知限制：

* **CPU only**：本机 `torch 2.9.1+cpu`，没有 CUDA。图标描述（Florence-2）在 CPU 上是数量级最慢的一环：
  1080×2400 真机截图、60 个图标、`--caption-batch 16` 时单次 `/parse` **实测 147.9 秒**，其中
  Florence-2 占绝大部分（单 crop generate ≈ 2.2s）；`--no-caption` 同一张图 **实测 14.6 秒**。
  要交互式使用请走 `--no-caption`，或上 GPU。
* **`--caption-batch`**：显存/内存与延迟的权衡；CPU 下建议 8–16，GPU 下可到 32+（上游默认 128）。
* **`transformers` 必须 pin 4.46.x**：见 §4 的三行实测对照表；升级到 4.5x/5.x 会导致 Florence-2 完全装不起来
  （服务会以退出码 2 启动失败并打印原因，不会静默降级成假结果）。
* **easyocr 首次运行会联网**下自己的模型（`~\.EasyOCR\model`，detection + recognition 共约 100 MB）；
  之后离线可用。服务启动时创建 `Reader` 就会触发，首次启动会慢。
* **Florence-2 远程代码首次运行会联网**（几个小 `.py`，缓存在 `HF_HOME`；本机 `HF_HOME=E:\MageVL\hf`）。
  断网且缓存为空时该路径失败，服务会打印两条加载路径的原始报错并以退出码 2 退出。
* **文字框来自 easyocr（英文模型）**：中文界面文本靠端侧 OCR / 无障碍节点更准，本服务主要贡献图标语义。
  实测真机截图上会出现 `H03 GG1l`、`#Rz` 这类英文模型的误识别，`label == text` 按契约原样返回，未做美化。
* **图标与文字重叠**：默认丢弃与文字框 IoU > 0.6 的图标，避免 YOLO 在文字上误检；如需要全部保留，
  可改 `server.py` 顶部的 `ICON_TEXT_IOU`（当前 0.6）。
* **`box_threshold` 默认 0.05 会检出很多框**（实测一张 1080×2400 截图 60 个图标）；Android 侧靠
  `max_elements`/`prefs.somMaxElements` 截断，服务端只按 `max_elements` 保序截断。
* `--backend upstream` 未验证（缺 paddleocr 等上游依赖）。
* JPEG 输出的标注图固定 `quality=88`，尺寸与提交图一致（不做任何缩放）。

## 6. 文件清单

```
server.py             HTTP 服务（契约实现；mock / lite / upstream 三个后端）
requirements.txt      lite 后端 pin 版本（= 实测安装版本）
download_weights.ps1  权重下载入口（默认 hf-mirror，失败回落官方站）
download_weights.py   单端点下载实现（HF_ENDPOINT 必须在 import hub 前设置）
verify_api.py         契约自检客户端（真实 HTTP，逐字段校验 + 错误路径）
util/                 上游 OmniParser 原样 vendored（只读，只复用 yolov9.py）
third_party/          上游 LICENSE / README / requirements 溯源
weights/              下载的权重（.gitignore，不入库）
run/                  运行期产物：日志、标注图、verify*.json（.gitignore，不入库）
.venv/                独立虚拟环境（.gitignore，不入库）
```

## 7. 实测记录（2026-10-10，本机 Windows / CPU / Python 3.12.7）

以下全部是**真跑出来的**命令与输出，产物在 `run\`（`run\*.json`、`run\*.log` 不入库）。

### 7.1 依赖与权重

```
.\.venv\Scripts\python.exe -m pip install -r requirements.txt -i https://pypi.tuna.tsinghua.edu.cn/simple   # exit 0
.\.venv\Scripts\python.exe -m pip install "transformers==4.46.1" -i https://mirrors.aliyun.com/pypi/simple/  # exit 0（tuna 当时证书过期）
powershell -ExecutionPolicy Bypass -File .\download_weights.ps1                                             # exit 0
```
`download_weights.ps1` 输出末尾（hf-mirror 失败 → 官方站成功，两个端点都打印了原始报错）：
```
[download] COMPLETE via https://huggingface.co; fetched 1367.6 MB this run
[download]   icon_detect_v3/model.pt  281.2 MB
[download]   icon_caption_florence/model.safetensors  1083.9 MB
[download_weights] exit=0
```

### 7.2 real 模式缺权重 → 退出码 2（不是"起来装死"）

```
> .\.venv\Scripts\python.exe server.py --backend lite --weights .\run\empty-weights --port 8011
[fatal] mode=real but weights are missing (3):
  - run/empty-weights/icon_detect_v3/model.pt
  - run/empty-weights/icon_caption_florence/config.json
  - run/empty-weights/icon_caption_florence/model.safetensors
[fatal] download the weights with one of:
  powershell -ExecutionPolicy Bypass -File "...\download_weights.ps1"
  D:\Python312\python.exe "...\download_weights.py" --weights-dir "...\run\empty-weights" --endpoint https://hf-mirror.com
REAL_NO_WEIGHTS_EXIT=2
```

### 7.3 mock（`server.py --mock`，端口 8012，跑完即关）

* `GET /health` → `{"ok": true, ..., "mode": "mock", "backend": "lite", "device": "cpu", "caption_enabled": true, ...}`
* `POST /parse`（真机 `shots\q2.png`，1080×2400，annotate=true）→ HTTP 200，`elapsed_ms=0`，4 个元素，
  label 全部带 `mock-` 前缀：
```
{"type":"text","label":"mock-关闭","text":"mock-关闭","bbox_ratio":[0.05,0.05,0.25,0.09],"bbox_px":[54,120,270,216],"interactable":true,"source":"ocr","id":1}
{"type":"text","label":"mock-A. 对",  ... "id":2}
{"type":"icon","label":"mock-magnifying glass","text":"", ... "id":3}
{"type":"icon","label":"mock-back arrow",       "text":"", ... "id":4}
```
* `annotate=true` 的 som 图解码后 **1080×2400**，与提交图一致。
* 契约自检：`.\.venv\Scripts\python.exe verify_api.py --base-url http://127.0.0.1:8012 --image ..\shots\q2.png --annotate --check-errors --out run\verify-mock-8012.json`
  → **`checks: 93  passed: 93  failed: 0`，退出码 0**。
* 错误路径（同一轮）：非法 JSON→400、坏 base64→400、缺 `image_base64`→400、非图片 base64→400、
  13 MB 图→413（`{"ok": false, "error": "image too large: 13631488 bytes > 12582912 bytes"}`）、未知端点→404。

### 7.4 real + lite + cpu，`--no-caption`（端口 8011）

```
.\.venv\Scripts\python.exe server.py --backend lite --weights .\weights --device cpu --port 8011 --box-threshold 0.05 --no-caption
.\.venv\Scripts\python.exe verify_api.py --base-url http://127.0.0.1:8011 --image ..\shots\q2.png --annotate --out run\verify-real-8011-nocaption.json
```
* 启动日志：`[lite] icon detector loaded ...` / `[lite] easyocr ready (gpu=False)` /
  `[start] ... mode=real backend=lite device=cpu caption_enabled=False` / `listening on http://0.0.0.0:8011`
* `shots\q2.png`（1080×2400）：**`elapsed_ms=14615`**（wall 14684 ms），**83 个元素** =
  **23 text + 60 icon**（source 分别 `ocr`/`icon`；`--no-caption` 时 60 个图标 label 全是 `"icon"`）。
* 前 5 条原文（同一次响应）：
```
{"type":"text","label":"13:13",     "text":"13:13",     "bbox_px":[160,51,260,89],    ... "id":1}
{"type":"text","label":"Hitit",     "text":"Hitit",     "bbox_px":[340,76,362,84],    ... "id":2}
{"type":"text","label":"H03 GG1l",  "text":"H03 GG1l",  "bbox_px":[709,45,823,89],    ... "id":3}
{"type":"text","label":"4",         "text":"4",         "bbox_px":[972,52,1000,86],   ... "id":4}
{"type":"text","label":"#Awito",    "text":"#Awito",    "bbox_px":[296,156,544,218],  ... "id":5}
```
* `shots\quiz.png`（1080×2400，annotate 缺省 false）：**`elapsed_ms=17142`**，80 个元素，
  且响应里**没有** `som_image_base64`；`checks: 1071 passed: 1071 failed: 0`。
* `shots\q2.png` + annotate=true：som 图 **1080×2400**（与提交图一致），
  `checks: 1111 passed: 1111 failed: 0`，**退出码 0**。

### 7.5 real + lite + cpu，全量 caption（端口 8010）

```
.\.venv\Scripts\python.exe server.py --backend lite --weights .\weights --device cpu --port 8010 --box-threshold 0.05 --caption-batch 16
.\.venv\Scripts\python.exe verify_api.py --base-url http://127.0.0.1:8010 --image ..\shots\q2.png --annotate --out run\verify-real-8010-caption.json
```
* 启动日志含：`[lite] Florence-2 caption model loaded: ...\weights\icon_caption_florence (dtype=torch.float32, trust_remote_code=True)`，
  `[start] ... caption_enabled=True`。
* `GET /health` → `{"ok": true, "service": "omniparser-service", "version": "1.0", "mode": "real", "backend": "lite", "device": "cpu", "caption_enabled": true, "weights": {"som_model": "weights/icon_detect_v3/model.pt", "caption_model": "weights/icon_caption_florence"}, "uptime_s": 67.5}`
* `shots\q2.png`：**`elapsed_ms=147873`**（wall 147940 ms），83 个元素 = 23 text + 60 icon；
  图标语义**全部是真 caption**（60/60 非占位 `"icon"`，53 个不同字符串），前 12 条：
```
A mozilla firefox logo. | A thumbs up symbol. | A simple math problem or problem related to math equations.
A computer mouse icon. | A simple line or shape. | A green square shape. | A green square shape.
A thumbs up or thumbs up symbol. | The number "H" or "One". | A bar code with the number 4. | Number forty. | number 4.
```
* `checks: 1111 passed: 1111 failed: 0`，**退出码 0**；som 图 1080×2400。

### 7.6 耗时对比（同一张 `shots\q2.png`，1080×2400，CPU，`--caption-batch 16`）

| 模式 | 单次 `/parse` 服务端 `elapsed_ms` | 元素 | 说明 |
|---|---|---|---|
| mock | 0 | 4 | 不加载模型，仅链路自测 |
| real + `--no-caption` | **14615**（≈14.6 s） | 83（23 text + 60 icon） | easyocr 11.7 s + YOLO 1.9 s + annotate 画图 |
| real + 全量 caption | **147873**（≈148 s） | 83（23 text + 60 icon） | 60 个图标 × Florence-2；CPU 上这就是上限 |

**说实话的口径**：CPU 下全量 caption 约 **2.5 分钟/张**，不适合交互式；要用图标语义必须 `--no-caption`
或上 GPU。两种模式都是**真实结果**，`--no-caption` 只是契约明确允许的合法降级（icon label 固定 `"icon"`），
不冒充全量 caption。

### 7.7 推理串行化（`threading.Lock`）

用**确定性**测试证明，而不是靠计时（计时会被别的客户端排队干扰）：
`run\probe_lock.py` 把 `backend.parse` 换成「记录同时在里面的线程数 + sleep 0.3s」的桩，然后 5 个线程
同时打 `Service.parse`：

```
> .\.venv\Scripts\python.exe run\probe_lock.py
threads=5 stub_sleep=0.3s wall=1.50s (serialized ~1.5s)
max concurrent threads inside backend.parse: 1
all responses ok: True
PASS: exactly one inference at a time, and total wall time ~= N * inference time    (exit 0)
```

补充说明（诚实口径）：另外用 `run\probe_concurrency.py` 对 8011 发过并发真实请求，那一轮时间数据
**不可作为串行化证据**——同一实例上还有 Android 真机验收的请求在排队，单个请求的 `elapsed_ms` 里
包含等锁时间（实测 38.5s / 27.0s 而单独跑是 14.6s，`sum(elapsed_ms)=65.5s > wall=38.6s` 正是"有排队"的
特征）。锁本身由上面的桩测试证明。

