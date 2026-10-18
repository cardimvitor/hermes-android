# Próximos passos (para retomar em outra sessão)

Leia antes: `docs/PLANO.md` (plano e validações, seção 2.5 = o que já foi confirmado),
`docs/BUILD.md` (comandos de build e testes) e a PR #1.

## Estado em 2026-10-18
- Branch de trabalho: `ccr-d0119ac9-czdhx3`. `main` = `8f7d3b5` (base da PR #1).
- PR #1 em draft, com os testes de host passando (16) e o harness Kotlin (5 checks).
- Nada foi gerado como APK ainda.
- Bloqueio conhecido: o proxy da sessão negou `dl.google.com`, `chaquo.com` e
  `www.python.org` (403 no CONNECT), mesmo com "all domains allowed" na configuração.
  A configuração só vale em sessão nova. Se a sessão nova ainda negar, a política
  está em nível de organização e precisa de um administrador.

## Passo 1: verificar a rede (sempre o primeiro)
```bash
for h in https://dl.google.com/android/repository/repository2-3.xml https://chaquo.com/ https://www.python.org/ftp/python/; do
  curl -sS -o /dev/null -w "%{http_code} $h\n" --max-time 20 "$h" 2>&1 | tail -1
done
```
Esperado: `200` nos três. Se aparecer `000` ou erro de proxy, pare aqui. Não tente
contornar: informe o usuário qual host foi negado e aguarde a liberação.

## Passo 2: rodar os testes de host (não dependem de rede)
```bash
uv venv -p 3.14 /tmp/hv
uv pip install -p /tmp/hv/bin/python -e upstream/hermes-agent pytest
/tmp/hv/bin/python -m pytest -q tests/host
```
Esperado: 16 passed.

## Passo 3: build do APK (só se o passo 1 passar)
1. `git submodule update --init --recursive` (fixa `upstream/hermes-agent` em `22063fc0`).
2. Instalar JDK 17 e Android SDK 35 (o AGP baixa o que faltar).
3. `python3` 3.14 no PATH (o Chaquopy usa para rodar o pip do build).
4. `./gradlew :app:assembleDebug`
5. Se falhar por dependência Python (wheel nativa ausente no índice do Chaquopy), registre
   o nome do pacote e a versão. Não troque versões sem registrar.

## Passo 4: gate de 16 KB (depois do passo 3)
```bash
python3 tools/check_elf_16kb.py app/build/intermediates/merged_native_libs
```
Esperado: `failed=0`. Qualquer `FAIL` é bloqueio para publicar.

## Passo 5: validação em aparelho ou emulador
Ainda não feita. Roteiro: abrir o app, aguardar "Pronto", enviar uma mensagem, confirmar
que a resposta aparece e que um comando perigoso gera o diálogo de aprovação.

## Pendências do plano (Fase 5 em diante)
- Armazenar chaves de API no Android Keystore (hoje não há tela de chave).
- Login dos provedores (Claude, ChatGPT, DeepSeek) conforme `docs/PLANO.md`.
- Verificar a licença do Chaquopy antes de qualquer distribuição.

## Regras que valem em qualquer sessão
- Não usar o nome do modelo nem "Claude" em mensagens de commit, nem coautor.
- Não fazer force-push em `main`, sem autorização explícita do usuário.
- Após push, abrir a PR (draft) se ainda não existir; a PR #1 já existe.

## Resultado da tentativa de build (2026-10-18)
- Rede: `dl.google.com`, `chaquo.com` e `www.python.org` responderam (200/302).
- Passo 2 (`tests/host`): 16 passed.
- Submódulo inicializado em `22063fc0`. SDK 35 instalado em `~/android-sdk`. JDK 17 (Temurin) apontado ao Gradle por `-Porg.gradle.java.installations.paths`.
- Maven Central retorna 429 com rajadas; o build resolveu as dependências com retry.
- Locale: sem `LANG=C.UTF-8` o `findDebugPythonCommand` do Chaquopy gera caminho corrompido. Com UTF-8 passa.
- **Bloqueio (passo 3.5):** `installDebugPythonRequirements` falha em
  `firecrawl-anydoc==0.2.4` ("from versions: none"). O pacote só tem wheels `musllinux`/`win_amd64` e sdist no PyPI, sem wheel Android. O pin vem de `upstream/hermes-agent/pyproject.toml`, linha 66 (dependência principal, `python_version >= '3.14'`), e é mantido pelo exportador `tools/export_android_requirements.py`. Não foi trocado nem removido: decisão de produto, a ser tomada pelo responsável.
- Passos 4 (gate 16 KB) e 5 (aparelho) não executados: dependem de APK.

## Atualização: exclusão de firecrawl-anydoc e novo bloqueio
- `tools/export_android_requirements.py` agora exclui `firecrawl-anydoc` (sem wheel Android). `app/requirements-android.txt` regenerado. Consequência: leitura de PDF indisponível no APK.
- Próximo bloqueio: `installDebugPythonRequirements` falha em `cryptography==50.0.1` (sem wheel Android).
- Causa raiz: o índice do Chaquopy (`https://chaquo.com/pypi-13.1/<pacote>/`) não tem wheels Android `cp314` para os binários. Os mais recentes são `cp313` (cryptography 42.0.8, Pillow, psutil). `resvg-py` e `pydantic-core` não aparecem no índice.
- Decisão necessária: (a) Python 3.13 no APK, o que quebra a exigência `>=3.14` e as dependências gated a 3.14 do upstream; (b) aguardar wheels cp314 do Chaquopy; (c) compilar as wheels com a toolchain do Chaquopy.
