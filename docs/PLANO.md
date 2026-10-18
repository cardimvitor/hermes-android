# Plano: Hermes Agent (Nous Research) nativo no Android

> Estado de referência: `NousResearch/hermes-agent` `main` @ `22063fc0` (2026-10-18).
> Todas as afirmações sobre o upstream abaixo foram verificadas nesse commit.

## 0. Resumo executivo

**Objetivo:** um APK autônomo (sem Termux, sem servidor externo, sem root) que roda o
Hermes Agent no próprio aparelho, com uma UI de chat nativa (Kotlin + Jetpack Compose) para
mandar tarefas e receber respostas/notificações, com login nos provedores:

| Provedor | Como funciona no desktop hoje | Viável no Android? |
|---|---|---|
| **Claude (Anthropic)** | OAuth PKCE do Claude Code (`agent/anthropic_credentials.py`), ou `ANTHROPIC_API_KEY` | Sim, mesmo fluxo (cola o código). **Ressalva:** só funciona com plano **Max + créditos de "extra usage"**; Pro não funciona (doc oficial do Hermes). |
| **ChatGPT / OpenAI (Codex)** | Device-code OAuth em `auth.openai.com` (`hermes_cli/auth_codex.py`), backend `chatgpt.com/backend-api/codex` | Sim, é o fluxo mais fácil no celular (mostra um código, abre o navegador). Também `OPENAI_API_KEY`. |
| **DeepSeek** | Só API key (`plugins/model-providers/deepseek`) | Sim. **Não existe "assinatura" DeepSeek para API** — é chave + saldo pré-pago. |

### Requisitos não negociáveis
- **Entrega = um único `.apk`** instalável direto (sideload), sem Termux, sem root, sem app auxiliar.
- **Tudo roda dentro do Android:** o agente, o Python, as ferramentas, as sessões, a memória,
  os agendamentos e as credenciais ficam no aparelho.
- **Sem VPS, sem servidor próprio, sem backend intermediário, sem proxy.** O APK fala
  **direto** com as APIs oficiais dos provedores (`api.anthropic.com`,
  `chatgpt.com/backend-api/codex` / `api.openai.com`, `api.deepseek.com`).
- A **única** coisa fora do aparelho é a inferência do modelo, que por definição roda nos
  servidores da Anthropic/OpenAI/DeepSeek — é isso que a assinatura/chave paga. Não há
  como usar a assinatura do Claude/ChatGPT sem chamar a API deles.
- Nenhuma ferramenta da v1 depende de serviço de terceiros configurado por nós.

**Decisão de arquitetura (recomendada):** *não reescrever* o agente. O Hermes tem >20 mil
arquivos, Python 3.14, e muda diariamente. "100% nativo" aqui significa:

1. **APK único**, sem depender de Termux, Node, Docker ou servidor remoto;
2. **UI 100% nativa** (Kotlin/Compose), sem WebView e sem TUI;
3. **Núcleo do agente = o próprio Hermes em Python**, rodando num **CPython 3.14 oficial
   para Android (PEP 738)** embutido no APK, num processo/serviço Android separado;
4. UI ⇄ agente via o **protocolo JSON-RPC do `tui_gateway`** (o mesmo que o Desktop e o
   dashboard usam), com tipos Kotlin gerados dos contratos Pydantic.

Reescrever o loop do agente em Kotlin fica como alternativa (seção 9), não recomendada.

---

## 1. Como o upstream está hoje (fatos relevantes)

- **Android já é alvo Tier 2** via Termux (APT assinado, só aarch64). A doc atual diz
  literalmente: *"Termux is currently broken"* — prova de que o caminho é frágil e que
  qualquer coisa nossa precisa fixar versão (pin) do upstream.
- O build Termux (`scripts/termux/*`) já compila wheels nativas com tag
  `android_24_arm64_v8a` (PEP 738) num container `termux-docker`. **Mas** linka contra o
  Python do Termux com prefixo fixo `/data/data/com.termux/files/usr` → **não serve
  diretamente num APK próprio**. Serve como *receita* (lista de pacotes que precisam de
  build, gates de import, retag).
- `pyproject.toml` já trata `sys_platform == 'android'` (ex.: `psutil` fixado num commit
  git para Android; `uvloop` fora porque não compila).
- `tui_gateway/` = backend JSON-RPC (stdio **e** WebSocket via `tui_gateway/ws.py`),
  com contratos declarados em Pydantic em `tui_gateway/contracts/` e gerados para
  TypeScript. Tem *server→client requests* (`approval`, `clarify`, `secret`, …) —
  exatamente o que precisamos para notificações/aprovações no celular.
- Provedores são plugins (`plugins/model-providers/<nome>/`): `anthropic`,
  `openai-codex`, `deepseek` existem.
- Credenciais vão para `~/.hermes/` (`auth.json`, `.anthropic_oauth.json`, `.env`); há
  backends de cofre plugáveis em `agent/vault_backends/`.
- Python oficial para Android: binários aarch64 e x86_64 publicados em
  python.org a partir do 3.14.

---

## 2. Problemas e limitações (o que vai dar errado e como tratar)

### 2.1 Runtime / build
| # | Problema | Impacto | Mitigação |
|---|---|---|---|
| R1 | Hermes exige **Python 3.14** | Chaquopy/BeeWare podem atrasar | Usar o **embeddable oficial do python.org** (3.14.x aarch64 + x86_64) e um bootstrap JNI próprio (~300 linhas C/Kotlin). |
| R2 | Dependências com código nativo (pydantic-core, cryptography, Pillow, pillow-heif, resvg-py, httptools, watchfiles, psutil, ruamel.yaml.clib, jiter, …) | Não há wheels Android no PyPI para quase nenhuma | Pipeline próprio de wheels com NDK (cibuildwheel com plataforma `android` ou crossenv), reaproveitando a lista de `scripts/termux/build_wheels.py`. Rust (pydantic-core, cryptography, jiter) via `cargo-ndk`. |
| R3 | **Páginas de 16 KB** (exigência do Google Play para targetSdk ≥ 35) | `.so` não alinhada → crash em aparelhos 16 KB / rejeição na Play | Compilar tudo com NDK r28+ e `-Wl,-z,max-page-size=16384`; gate de CI que verifica alinhamento ELF de **toda** `.so`. |
| R4 | **W^X**: app com targetSdk ≥ 29 não pode `exec()` binários em diretório gravável | Ferramenta *terminal*, `git`, `rg`, `node`, `uv`, MCP via `npx` quebram | Binários só via `jniLibs` (`lib*.so`, `extractNativeLibs=true`) executados de `nativeLibraryDir`; aceitar que instalação de binários em tempo de execução é impossível. `dlopen` de extensões Python a partir dos dados do app continua permitido. |
| R5 | Instalação dinâmica (PM/`uv`/lazy installs, `hermes update`) | Tenta baixar/instalar pacotes → falha | Desligar: todo o conjunto de dependências é fechado no build; updates só via novo APK. Patch/feature-flag `HERMES_MANAGED_RUNTIME=android-apk`. |
| R6 | Tamanho | Python + stdlib + wheels ≈ 80–150 MB | App Bundle com split por ABI; remover testes/`__pycache__` da stdlib, pré-compilar `.pyc`, excluir extras não usados (desktop, voice, matrix, nemo). |
| R7 | Upstream muda todo dia e quebra Android (Tier 2) | Regressões constantes | Upstream como **submódulo fixado em tag estável**, patches mínimos numa pasta `patches/`, CI que tenta o próximo tag automaticamente e abre PR. |
| R8 | Código que assume Linux/glibc (`sys.platform == 'linux'`, `/proc`, `os.fork`, `/tmp`) | Erros em tempo de execução | Rodar a suíte de testes do upstream no emulador (seção 6) e corrigir/upstreamar. `TMPDIR`/`HOME` apontando para o diretório do app. |

### 2.2 Sistema Android
| # | Problema | Mitigação |
|---|---|---|
| A1 | Processo morto em segundo plano / Doze / App Standby | **Foreground Service** em processo separado (`:agent`) enquanto há tarefa rodando; tipo `specialUse` (requer justificativa na Play) ou `dataSync` (limite de ~6 h/dia no Android 15+). Retomada de sessão após morte (o `tui_gateway` já tem `session.resume`). |
| A2 | Cron do Hermes (`cron/`, croniter) não roda com app fechado | Ponte: o Python calcula o próximo disparo → Kotlin agenda `AlarmManager`/`WorkManager` → acorda o serviço. |
| A3 | Sem `/bin/bash`, `git`, `rg`, `node` | Shell = `/system/bin/sh` (toybox). Opcional: empacotar `busybox`, `rg`, `git` como `jniLibs`. Ferramentas de arquivo/edição do Hermes funcionam em Python puro. |
| A4 | Armazenamento com escopo (scoped storage) | Workspace dentro de `filesDir`; importar/exportar via SAF (Storage Access Framework). Evitar `MANAGE_EXTERNAL_STORAGE` (recusado na Play). |
| A5 | Ferramentas inviáveis: Docker, browser com Chromium local, computer-use de desktop, voz via deps sem wheel, MCP stdio que precisa de Node | Desabilitar no toolset Android; navegação web feita no próprio aparelho (web fetch/extract em Python e, na Fase 7, um `WebView` invisível controlado pela ponte JNI no lugar do Chromium); MCP stdio só com servidores Python empacotados no APK; voz (fase opcional) via `SpeechRecognizer`/`TextToSpeech` nativos. |
| A6 | Memória: celulares matam processos > ~1–2 GB | Limitar contexto/compressão; sem modelos locais. |
| A7 | Rede/certificados | Hermes usa `truststore` (cert store do SO). Validar que funciona no Android; senão, fallback para `certifi`. |

### 2.3 Autenticação / assinaturas
| # | Problema | Mitigação |
|---|---|---|
| O1 | **Claude**: o OAuth reaproveita o `client_id` do Claude Code com redirect fixo `console.anthropic.com/oauth/code/callback` — não dá para usar deep link do app | Fluxo "abrir Custom Tab → usuário copia o código `code#state` → cola no app" (detectar no clipboard para facilitar). É o mesmo fluxo do CLI. |
| O2 | **Claude: termos e cobrança.** Uso por terceiros do OAuth do Claude Code consome **apenas créditos de extra usage do Max**; Pro não funciona; a Anthropic pode restringir a qualquer momento | Deixar explícito na UI; sempre oferecer `ANTHROPIC_API_KEY` como alternativa; tratar 401/403/429 com mensagem clara. Validar a política vigente antes de publicar. |
| O3 | **OpenAI/Codex**: elegibilidade por plano e cota não documentadas | Device-code funciona bem no celular. Mostrar o uso/erros de cota retornados (`account_usage.py`). Alternativa: `OPENAI_API_KEY`. |
| O4 | **DeepSeek** não tem assinatura OAuth | Somente API key; UI de "colar chave" + teste `/models`. |
| O5 | Rotação de refresh token (uso único) e corrida entre processos | Um único processo dono das credenciais (`:agent`); UI nunca toca nos tokens. |
| O6 | Segurança dos segredos | `HERMES_HOME` em `filesDir` (sandbox + criptografia FBE); adicionar backend de cofre `android_keystore` em `agent/vault_backends/` (chave AES-GCM no Android Keystore, ponte JNI) para `auth.json`/`.env`. `android:allowBackup="false"`, sem tokens em logcat. |

### 2.4 Distribuição
- Google Play: justificativa do foreground service, política de "código baixado/executado"
  (não baixar código Python em runtime — skills baixadas pelo agente são texto/prompt, mas
  *plugins* com código devem ficar desligados na build da Play).
- Alternativa: distribuir por GitHub Releases/F-Droid primeiro (menos restrições).

---

## 2.5 Revisão após validação (2026-10-18)

Antes de implementar, cada premissa foi testada. Resultado:

| Premissa do plano original | Resultado | Mudança |
|---|---|---|
| Python 3.14 para Android precisa de bootstrap JNI próprio (Fase 0) | **Falsa.** O Chaquopy publica `com.chaquo.python:target:3.14.8-0` no Maven Central (acessível). | Usar **Chaquopy 17.1.0** para embutir o Python. Bootstrap próprio removido. |
| Transporte por WebSocket em 127.0.0.1 com token | **Desnecessário.** `tui_gateway/entry.py` lê `sys.stdin` e escreve em `sys.stdout`, e `tui_gateway/entry.py` já prevê "embedded launchers". | Ponte **em memória** (`app/src/main/python/hermes_bridge.py`): sem porta, sem token. |
| Gateway sobe com `hermes gateway` | Verificado: `_serve()` roda headless. `session.list`, `session.create`, erro `-32601` e `gateway.ready` OK (Python 3.14, `tests/host`). | Ponto de entrada: `_serve()` em thread própria. |
| Desligar instalação preguiçosa | **Confirmado necessário:** no teste aparece `boto3 lazy install did not complete` e `Could not resolve the stable source release: 403`. | `security.allow_lazy_installs` desligado e self-update desligado na build Android (Fase 2). |
| `requirements` a partir do `pyproject` | Marcadores `python_version >= '3.14'` são avaliados pelo Python do **computador de build**, não pelo alvo. | `tools/export_android_requirements.py` remove os marcadores; `nemo-relay` excluído (como no Termux). |
| Gate de 16 KB | Verificador testado: detecta alinhamento 4 KB (45/45 libs do host x86_64 falham, como esperado; libc também). | `tools/check_elf_16kb.py`. Só prova a detecção; o APK real precisa passar nele. |

**Não verificado neste ambiente** (bloqueado pela rede do sandbox):
- Android SDK/NDK e Chaquopy pip (`chaquo.com`) — `dl.google.com`, `python.org`, `chaquo.com` retornam 403/erro de proxy.
- Build do APK e execução em aparelho/emulador. A checagem de Kotlin usou o compilador do Kotlin (Maven Central): `GatewayClient.kt` compila sem erros; os demais arquivos só falham por símbolos do Android/Compose/Chaquopy que não estão no host.
- **Licença do Chaquopy:** a busca não retornou os termos. Precisa ser verificada no site do Chaquopy antes de qualquer distribuição. Se for restritiva, a alternativa é o embeddable oficial do python.org com bootstrap JNI próprio.
- Disponibilidade no índice do Chaquopy das wheels nativas (`pydantic-core`, `cryptography`, `Pillow`, `resvg-py`, ...). Se faltarem, é preciso compilar com o NDK (Fase 1).

## 3. Arquitetura alvo (atualizada)

```
┌──────────────────────────── APK (arm64-v8a [+ x86_64 p/ emulador]) ───────────────────────────┐
│ Processo :ui (Kotlin/Compose)                 │ Processo :agent (Foreground Service)          │
│  • Chat (lista de sessões, mensagens,         │  • libpython3.14.so (CPython oficial)         │
│    streaming, anexos, markdown)               │  • stdlib + site-packages (wheels Android)    │
│  • Telas de login (Claude/Codex/DeepSeek)     │  • hermes-agent (submódulo fixado + patches)  │
│  • Aprovações/clarify/secret (dialogs +       │  • tui_gateway em modo servidor               │
│    notificações com ações)                    │  • HERMES_HOME = filesDir/hermes              │
│  • Cliente JSON-RPC (tipos gerados)           │  • ponte JNI: keystore, alarmes, notificação  │
│            ▲                                  │            ▲                                  │
│            └──── socket Unix abstrato / WS 127.0.0.1 + token ────┘                            │
└───────────────────────────────────────────────────────────────────────────────────────────────┘
```

- Transporte: **canal em memória** entre Kotlin e Python (ver 2.5). O Kotlin chama
  `hermes_bridge.send(linha)`; o Python entrega cada frame por `sink.onLine(linha)`.
  Não há porta nem token. O `tui_gateway/ws.py` fica sem uso no APK.
- Processo separado: crash do Python não derruba a UI; o serviço reinicia e a UI
  reconecta com `session.resume` / `session.events.since`.

### Estrutura do repositório (`hermes-android`)
```
upstream/hermes-agent/      submódulo git fixado em tag estável
patches/                    patches mínimos sobre o upstream (alvo: upstreamar todos)
python-build/               download/verificação do CPython Android + build de wheels (Docker)
  wheels.lock               lista fechada (nome==versão) derivada do uv.lock do upstream
app/                        módulo Android (Kotlin/Compose)
  src/main/cpp/             bootstrap JNI do CPython
  src/main/python/          launcher Python (android_main.py) + ponte JNI
contracts-kotlin/           geração de tipos Kotlin a partir de tui_gateway/contracts
tools/                      scripts de verificação (alinhamento 16 KB, tamanho, licenças)
docs/                       este plano + ADRs
.github/workflows/          CI
```

---

## 4. Passo a passo da execução

Cada fase tem **entregáveis** e **critérios de saída (validação)**. Não avançar sem
cumprir os critérios.

### Fase 0 — Spike de viabilidade (1–2 semanas)
Objetivo: provar o caminho mais arriscado antes de qualquer UI.
1. App Android mínimo (Kotlin) com Chaquopy 17.1.0 / Python 3.14 (arm64 + x86_64) e
   `print(sys.version)` (revisado: não é mais bootstrap JNI próprio).
2. Compilar 3 wheels nativas "difíceis": `pydantic-core` (Rust), `cryptography` (Rust +
   OpenSSL), `Pillow` — com NDK r28+, 16 KB, tag `android_24_*`.
3. Rodar no aparelho: `import openai, httpx, pydantic, cryptography` e uma chamada HTTPS
   real (`truststore`) à API da DeepSeek com chave de teste.

**Validação:** app abre em aparelho físico arm64 e no emulador x86_64; os imports passam;
resposta HTTP 200; `tools/check_16kb.sh` passa em todas as `.so`. Se R2 ou R3 falharem
aqui, reavaliar (ex.: usar Chaquopy quando suportar 3.14).

### Fase 1 — Wheelhouse completo e reprodutível (2–3 semanas)
1. Fixar o upstream num tag estável (submódulo) e gerar `wheels.lock` a partir do `uv.lock`
   com marcadores `sys_platform == 'android'`, só core + extras necessários
   (`anthropic`, `acp` se útil), sem `[all]`.
2. Pipeline Docker (`python-build/`) que constrói todas as wheels nativas para
   `arm64-v8a` e `x86_64`, com cache, e roda o *gate offline*: instala o lock inteiro com
   `--no-index` a partir da wheelhouse (igual a `scripts/termux/build_wheels.py`).
3. Empacotar `site-packages` + `.pyc` pré-compilados como asset do APK.

**Validação:** build limpa do zero em CI produz bytes idênticos em duas execuções
(reprodutível); gate offline OK; script de import importa **todos** os módulos de
`agent/`, `tools/`, `tui_gateway/`, `providers/` sem erro no emulador; relatório de
tamanho ≤ 150 MB por ABI.

### Fase 2 — Hermes "headless" dentro do APK (2–3 semanas)
1. `android_main.py`: define `HERMES_HOME`, `HOME`, `TMPDIR`, `SSL_CERT_*`, flag
   `HERMES_MANAGED_RUNTIME=android-apk`; sobe o `tui_gateway` em modo WebSocket em
   127.0.0.1:porta aleatória com token.
2. Foreground Service no processo `:agent` (notificação persistente "Hermes trabalhando").
3. Patches (`patches/`) para: desligar self-update, PM/lazy installs, toolsets inviáveis
   (A5); `hermes_platform.host` reconhecer "Android APK" (hoje reconhece Termux).
4. Rodar a suíte `pytest` do upstream (subconjunto core: `tests/agent`, `tests/tools`,
   `tests/tui_gateway`, `tests/hermes_cli` sem desktop) **dentro do emulador**.

**Validação:** um cliente de teste (script em Kotlin instrumentado) conecta, cria sessão,
manda "liste os arquivos do workspace" com DeepSeek por API key e recebe a resposta com
uso de ferramenta. ≥ 95% do subconjunto de testes passa; cada falha restante tem issue
ou patch. Matar o processo `:agent` via `adb shell am kill` → serviço volta e a sessão é
retomada.

### Fase 3 — Contratos e cliente Kotlin (1–2 semanas)
1. Exportar JSON Schema dos modelos Pydantic de `tui_gateway/contracts/` e gerar
   data classes `kotlinx.serialization` (`contracts-kotlin/`).
2. Cliente JSON-RPC em Kotlin (coroutines/Flow): chamadas, eventos, *server→client
   requests*, reconexão, `client.capabilities {server_requests: true}`.

**Validação:** teste de contrato em CI: para cada método/evento, round-trip
Python→JSON→Kotlin→JSON→Python sem perda; CI falha se o upstream mudar um contrato e o
Kotlin não for regenerado.

### Fase 4 — UI de chat nativa (3–4 semanas)
1. Lista de sessões, tela de conversa com streaming token a token, markdown/código,
   indicador de ferramentas em execução (`tool_progress`), botão de interromper.
2. Envio de anexos (imagem da câmera/galeria, arquivos via SAF) → workspace.
3. Aprovações (`approval`, `clarify`, `secret`, `sudo`) como diálogos **e** notificações
   com ações (aprovar/negar/responder) quando o app está em segundo plano — isso é o
   "receber" do chat.
4. Seletor de modelo/provedor por sessão; tela de configurações (workspace, toolsets
   ligados, limites).

**Validação:** testes de UI (Compose testing + Espresso) para fluxos principais; teste
manual roteirizado (seção 6.3); acessibilidade (TalkBack) nos fluxos principais; sem ANR
em 30 min de uso contínuo (Android vitals locais / `adb shell dumpsys`).

### Fase 5 — Provedores e assinaturas (2 semanas)
1. **OpenAI/Codex (device code):** UI mostra o `user_code`, botão "Copiar e abrir" (Custom
   Tab em `auth.openai.com`), polling feito pelo Python (`_codex_device_code_login`
   adaptado para não usar `input()`/`print()` — expor como métodos RPC
   `auth.codex.start/poll`).
2. **Claude (PKCE):** RPC `auth.anthropic.start` devolve a URL; Custom Tab; usuário cola
   `code#state` (detecção automática no clipboard); RPC `auth.anthropic.finish` faz a troca
   (reaproveita `run_hermes_oauth_login_pure`, refatorado para separar URL/troca). Aviso
   claro "requer Max + extra usage".
3. **DeepSeek e chaves de API** (Anthropic, OpenAI, DeepSeek): colar chave + botão "Testar".
4. Backend de cofre `android_keystore` (O6); confirmar que refresh de token persiste após
   reiniciar o app e após rotação.
5. Fallback de provedores (`fallback-providers` do Hermes) configurável na UI.

**Validação:** matriz da seção 6.2 executada em aparelho real, com contas reais:
login, 1ª mensagem, refresh forçado (expirar token manualmente), logout, re-login,
reinício do aparelho. Nenhum token aparece em `adb logcat` nem em backups
(`adb backup`/Auto Backup desligado verificado).

### Fase 6 — Robustez em segundo plano e tarefas agendadas (2 semanas)
1. Ponte cron → `AlarmManager` (exato só se o usuário permitir; senão `WorkManager`).
2. Política de bateria: pedir isenção de otimização opcionalmente; pausar/retomar.
3. Notificação final da tarefa com resumo e deep link para a sessão.

**Validação:** `adb shell dumpsys deviceidle force-idle` durante tarefa longa → tarefa
continua (foreground) ou é retomada; job cron dispara com app fechado e tela desligada
(teste em Pixel e em Samsung/Xiaomi, que são mais agressivos); consumo de bateria medido
com `batterystats` em 1 h de uso.

### Fase 7 — Ferramentas no celular (2 semanas, incremental)
1. Habilitar e validar (tudo local): arquivos/edição, busca, web fetch/extract, memória, skills,
   compressão de contexto, subagentes.
2. Ferramenta de navegador on-device: `WebView` invisível (sem Chromium separado) exposta ao
   agente via ponte JNI (abrir URL, ler DOM/texto, clicar, screenshot).
3. Opcional: `busybox`, `rg`, `git` empacotados como `jniLibs` para a ferramenta terminal.
4. Opcional: ferramentas nativas Android expostas ao agente via ponte (notificação,
   calendário, compartilhar, clipboard), cada uma com permissão explícita.

**Validação:** suíte de "tarefas de ponta a ponta" (seção 6.4) passando nos 3 provedores;
captura de tráfego (proxy no emulador / `tcpdump`) confirma que o APK só fala com os
hosts dos provedores e os sites que o próprio agente acessa — nenhum servidor nosso.

### Fase 8 — Release (1–2 semanas)
1. Assinatura, App Bundle, splits por ABI, R8 para o código Kotlin, ProGuard keep para JNI.
2. Licenças: gerar tela de licenças (Hermes é MIT; checar todas as wheels/binários).
3. Distribuição inicial: GitHub Releases (APK assinado); depois Play (teste interno →
   fechado), com formulário do foreground service `specialUse`.
4. Processo de atualização do upstream: CI semanal tenta o próximo tag estável, roda tudo
   da seção 6 e abre PR.

**Validação:** Play pre-launch report sem crash; instalação limpa em Android 10, 12, 14,
15 e 16; upgrade do APK preserva sessões, memórias e logins.

**Estimativa total:** ~4–5 meses para 1 dev experiente em Android + Python; o maior risco
de prazo está nas Fases 0–1 (toolchain nativa).

---

## 5. Requisitos mínimos de plataforma
- `minSdk 26` (wheels são `android_24`, mas 26 simplifica foreground service/NIO);
  `targetSdk` = o exigido pela Play no momento (≥ 35).
- ABIs: `arm64-v8a` (produção) e `x86_64` (emulador/CI). Sem 32 bits.
- RAM recomendada: ≥ 6 GB.

## 6. Plano de validação (consolidado)

### 6.1 Automatizado (CI em todo PR)
| Gate | Ferramenta |
|---|---|
| Build reprodutível das wheels + gate offline | Docker + `pip install --no-index` |
| Alinhamento 16 KB de toda `.so` | `llvm-readelf` / `zipalign -c -P 16` |
| Import de todos os módulos do Hermes no emulador | instrumented test |
| Subconjunto `pytest` do upstream no emulador x86_64 (KVM no runner Linux) | `android.py`-style test runner |
| Contratos Kotlin ⇄ Pydantic | teste de round-trip |
| Testes de UI | Compose test / Espresso |
| Lint, detekt, testes unitários Kotlin | Gradle |
| Varredura de segredos no APK e logs | gitleaks + script próprio (grep de tokens no APK e no logcat) |
| Tamanho do APK por ABI (orçamento) | script |

### 6.2 Matriz de provedores (manual, aparelho real, por release)
| Caso | Claude OAuth (Max+extra) | Claude API key | Codex OAuth | OpenAI key | DeepSeek key |
|---|---|---|---|---|---|
| Login | ✓ | ✓ | ✓ | ✓ | ✓ |
| Mensagem simples + streaming | ✓ | ✓ | ✓ | ✓ | ✓ |
| Chamada de ferramenta | ✓ | ✓ | ✓ | ✓ | ✓ |
| Refresh de token (forçado) | ✓ | — | ✓ | — | — |
| Erro de cota/plano mostra mensagem clara | ✓ (Pro → erro claro) | ✓ | ✓ | ✓ | ✓ (saldo zero) |
| Persistência após reboot | ✓ | ✓ | ✓ | ✓ | ✓ |
| Fallback para outro provedor | ✓ | ✓ | ✓ | ✓ | ✓ |

### 6.3 Roteiro manual de UX
Criar sessão → anexar foto → pedir resumo → aprovar ação pela notificação com app em
segundo plano → interromper no meio → retomar → trocar de modelo → exportar arquivo via SAF.

### 6.4 Tarefas de ponta a ponta (regressão do agente)
1. "Crie um arquivo notas.md com X e depois edite a linha 2."
2. "Pesquise na web Y e resuma com fontes."
3. "Agende um lembrete diário às 9h" (cron → alarme).
4. Conversa longa que force compressão de contexto.
5. Skill criada pelo agente e reutilizada em nova sessão.

### 6.5 Não funcionais
- Cold start até chat utilizável ≤ 4 s (aparelho médio) — medir com Macrobenchmark.
- Memória do processo `:agent` ≤ 600 MB em uso normal.
- Sem crash/ANR em teste de estresse de 2 h (Monkey + script de mensagens).

## 7. Riscos principais (ordem de severidade)
1. **Política da Anthropic** sobre OAuth do Claude Code em apps de terceiros (pode ser
   bloqueado a qualquer momento; hoje só consome extra usage). → API key sempre disponível.
2. **Toolchain nativa** (Rust/C para Android + 16 KB) — maior risco técnico; atacado na Fase 0.
3. **Velocidade do upstream** (Tier 2, Termux quebrado hoje) — pin + patches mínimos + CI semanal.
4. **Google Play** (foreground service, execução de código) — começar fora da Play.
5. **Fabricantes agressivos com bateria** (Xiaomi, Samsung, Huawei) — documentar e testar.

## 8. Fora do escopo (v1)
Docker, Chromium local, computer-use de desktop, modelos locais, gateways de mensageria
(Telegram/Signal) rodando 24/7, voz/wake-word, assinatura Gemini, instalação de plugins
com código em runtime.

## 9. Alternativa considerada: núcleo reescrito em Kotlin
Reimplementar só o loop do agente + 3 provedores + ferramentas básicas em Kotlin.
- Prós: APK pequeno (~15 MB), sem toolchain Python, melhor bateria.
- Contras: deixa de ser o Hermes (sem skills, memória, compressão, subagentes, plugins,
  fallback, cron); manutenção divergente para sempre; refazer OAuth/refresh de 3 provedores.
- Conclusão: só faz sentido se o objetivo mudar para "um agente leve inspirado no Hermes".

## 10. Primeiros passos concretos (semana 1)
1. Adicionar o submódulo `upstream/hermes-agent` fixado no último tag estável.
2. Criar o projeto Gradle (`app/`) com Kotlin + Compose, `minSdk 26`, ABIs arm64/x86_64.
3. Script `python-build/fetch_cpython.sh` que baixa e verifica (hash) o embeddable 3.14.x
   oficial para Android.
4. Bootstrap JNI que inicializa o CPython e roda `print(sys.version)`.
5. Dockerfile de build das wheels começando por `pydantic-core`, `cryptography`, `Pillow`.
6. Gate de 16 KB no CI.
