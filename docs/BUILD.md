# Build e testes (estado: spike)

## Pré-requisitos (máquina de build)
- JDK 17
- Android SDK com `platforms;android-35` e `build-tools` (o AGP baixa o que faltar)
- Python 3.14 no PATH como `python3` (o Chaquopy usa para rodar o pip do build)
- Git com submódulos

## Clonar
```bash
git clone --recurse-submodules <repo>
# ou, depois de clonar:
git submodule update --init --recursive     # fixa upstream/hermes-agent em 22063fc0
```

## Testes de host (sem Android)
```bash
uv venv -p 3.14 .venv-host
uv pip install -p .venv-host/bin/python -e upstream/hermes-agent pytest
.venv-host/bin/python -m pytest -q tests/host
```
Cobertura atual:
- `test_bridge_host.py`: sobe o gateway real pela ponte em memória e confere `gateway.ready`,
  `session.list`, `session.create`, erro de método desconhecido e zero linhas fora de JSON.
- `test_check_elf_16kb.py`: detector de alinhamento 4 KB / 16 KB com ELFs sintéticos.
- `test_bridge_host.py::test_server_request_round_trip_through_bridge`: aprovação enviada pelo
  gateway, resposta do app pela ponte, resultado devolvido ao chamador.
- `test_export_requirements.py`: a lista Android não tem marcadores nem pacotes só de desktop.

## Dados de rede necessários para o build
O build precisa destes domínios liberados: `dl.google.com` (SDK e AGP),
`maven.google.com`, `repo1.maven.org`, `chaquo.com` (wheels do Chaquopy) e
`www.python.org` (somente se o Python for baixado manualmente).

## Harness Kotlin contra o gateway real
`tests/host/kotlin/run.sh` compila `GatewayClient.kt` e roda o gateway Python por pipes:
`client.capabilities`, `session.create`, `session.list`, erro de método e encerramento.
Precisa dos jars do kotlinc e das coroutines em `$JARS` (ver o script).

## Gate de 16 KB (sobre o APK construído)
```bash
python3 tools/check_elf_16kb.py app/build/intermediates/merged_native_libs
```
Só deve ser considerado válido sobre bibliotecas compiladas para Android.

## Build do APK
Próximos passos e verificações de rede para outra sessão: `docs/PROXIMOS_PASSOS.md`.

```bash
./gradlew :app:assembleDebug
```
**Não executado ainda**: o sandbox não alcança `dl.google.com` nem `chaquo.com`.

## Compilação de Kotlin sem SDK (checagem parcial)
Usada para validar sintaxe e tipos da lógica pura (`GatewayClient.kt`). Os erros
restantes são apenas símbolos do Android, Compose e Chaquopy, esperados sem o SDK.
