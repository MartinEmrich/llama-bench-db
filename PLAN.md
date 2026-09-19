# llama-bench-db

A llama-bench result database, to collect, compare and organize test runs of llama.cpp's benchmark tool across different configurations.

## Architecture

* Web application
* Data storage via SQL database. use backend-agnostic ORM, shall run on local SQLite, MariaDB/MySQL or PostgreSQL with no changes. Migration between them should be easy (via ex- and import).
* Classic Frontend and backend split, with a clean HTTP API also useable from scripts (does not have to be RESTful for the sake of it, be pragmatic).
 * Frontend: Use Vue3/Nuxt, keep it simple, both in design and implementation complexity.
 * Backend: If Spring Boot with Spring Data/Hibernate satisfies our need for a runtime-flexible schema (see below, results with previously unknown, but still sortable/queryable attributes), use it. If not, bail out immediately and present me alternatives (Rust or Java preferred, Javascript if necessary).

## Functionality

Core intent: Store and organize llama-bench results collected from various computers, GPUs, backends, models, and combinations of settings.
Import of a new result run shall be easy, frictionless and complete.
Later, the user shall be able to retrieve results, filtered by model, settings, computer, or a combination thereof.

## Data management

### Computers

The user shall be able to manage (CRUD) **Computers**, each entry is a single system (Maybe a PC, a laptop or a server, though that distinction is only reflected in the name).
Important fields

* name: a short name, to be displayed in tables
* description: A free text field only displayed maybe in a detailed page showing one computer
* version: Computers shall be versioned, with a timestamp as a version. User shall be able to add a new version (maybe changed some piece of hardware), and provides an edited description during that action.

### Models

Each Model (LLM) shall be identified by the main model name, the uploader, the uploaded model name (which may differ from the base model name, e.g. with suffixes like "-GGUF") and the quantization. These together usually form the identifier for running with llama.cpp, for example `unsloth/Qwen3.6-35B-A3B-GGUF:Q4_K_M` is Uploader "unsloth", Model "Qwen3.6-35B-A3B" and Quantization ("Quant") "Q4_K_M"

* name: Base model name, e.g. "Qwen3.5-4B"
* modelid: ID from huggingface, also contains the uploader, e.g. "unsloth/Qwen3.5-4B"
* quantization: specific quant used in the benchmark run, e.g. "Q4_K_M" or "IQ3_XXS". A String.
* size: Size in GiB (float) of the model. Can be left empty on entry.

The User shall be able to CRUD model entries. The create model form shall be able to parse a full Huggingface model id into the named fields.

### Results

Each result is linked to the computer (plus version) and the model used for the run.
Each result contains all relevant data distinct for a run, including all run parameters (like PP and TG size, KV cache quantization and other settings of llama-bench).
The user shall also be able to enter the device(s) and frameworks used (e.g. CPU, CUDA, Vulkan, SYCL, ROCm) possibly for multiple 

#### Import

The user should be able to import as friction-free as possible a new run result. Herefore, user shall be able to select the Computer and Model, and then cut&paste the result Markdown-table like console output into a text field in the form.
llama-bench-db shall then parse it, and extract all possible information for the run.

Two examples:

````
| model                          |       size |     params | backend    | threads | type_k | type_v |  fa |         lm | lazy_mode  |            test |                  t/s |
| ------------------------------ | ---------: | ---------: | ---------- | ------: | -----: | -----: | --: | ---------: | ---------- | --------------: | -------------------: |
| qwen35 4B Q4_K - Medium        |   2.54 GiB |     4.21 B | CPU        |       2 |   q8_0 |   q8_0 |   1 |       none | off        |           pp512 |          0.71 ± 0.00 |
| qwen35 4B Q4_K - Medium        |   2.54 GiB |     4.21 B | CPU        |       2 |   q8_0 |   q8_0 |   1 |       none | off        |           tg128 |          0.58 ± 0.00 |
````

````
| model                          |       size |     params | backend    | ngl | type_k | type_v | fa | ts           | mmap | dio |            test |                  t/s |
| ------------------------------ | ---------: | ---------: | ---------- | --: | -----: | -----: | -: | ------------ | ---: | --: | --------------: | -------------------: |
| qwen35 ?B Q4_K - Medium        |  15.58 GiB |    26.90 B | ROCm,Vulkan |  99 |   q8_0 |   q8_0 |  1 | 56.00/9.00   |    1 |   1 |           pp512 |         30.44 ± 0.24 |
| qwen35 ?B Q4_K - Medium        |  15.58 GiB |    26.90 B | ROCm,Vulkan |  99 |   q8_0 |   q8_0 |  1 | 56.00/9.00   |    1 |   1 |           tg128 |          9.51 ± 0.01 |
````

#### Data fields

Some of the most important fields:

Always there:
* model: This string is extracted from the model file or provided by llama.cpp's implementation. Store it with the result, but the actual model information is linked to the model selected by the user.
* size: total size of the model. If the user selected model size is empty, update it from this field. If it is already populated, compare this to it. If there is a notewothy difference (10%), Pop up a warning/confirmation dialog (the user might have selected the wrong model or quant)
* params: clear from the model itself, can be discarded
* backend: the **available** (not necessarily used) accelerated backends, in addition to CPU. cross-check against user entered device/backend, as well as the device entry if present.

Only there if specified on the command line. if absent, assume the default, or don't store them.
* ngl: Number of GPU layers. if absent, assume "-1", which means "all". If the number is high (>=99) still save it, but treat it in a later query/evaluation just as -1.
* type_k, type_v: KV Cache Quantization. if absent, each defaults to "f16".
* fa (boolean): Flash Attention, either 1 (on) or 0. defaults to 0 (off).
* ts: Tensor Split: A list of factors (float) designating how the `ngl` layers (or all layers) are split across multiple GPUs. Split by separator "/" or ";", and store as list.
* lm (string) or mmap: lm is "load-mode". defaults to "auto". if "mmap" is 1 (older versions), set to "mmap". other possible options include "none".
* threads: integer, number of CPU threads used.

Result values: Each run consists of two result lines. one with "test" being "pp" followed by an integer (number of prompt tokens), the other being "tg" followed by an integer (number of generated tokens), and their result, a float with a deviation across multiple runs.

Store both lines together as one result dataset with:

* pp_tokens
* tg_tokens
* pp_tps
* tg_tps
* pp_deviation
* tg_deviation

All other fields of the two lines must be identical; if they are not, reject the entry with an error message (likely a copy&paste mistake).

## Result presentation

let's start with a simple, paginated table, with one row for each result. Filterable and sortable by computer (plus version), base model, full model ID, quantization (sorted by the number, then the t-shirt size like section, e.g. IQ3_XS < Q4_0 < Q5_K_M), PP and TG tokens and speed (filter as a range).

That's it for now!
