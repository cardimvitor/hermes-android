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

## Python 3.13 como alternativa (testado em 2026-10-18)
- Testado com `version = "3.13"` no Chaquopy e `python3` 3.13 no PATH. Falha em `cryptography==50.0.1`: o índice só tem `42.0.8` para cp313. Revertido para 3.14 (exigência do upstream).
- Pins com wheel cp313 Android disponível: `cryptography` 42.0.8 (pin: 50.0.1), `psutil` 7.1.3 (pin: 7.2.2), `Pillow` 11.0.0 (pin: 12.3.0). Baixar essas pins troca correções de segurança do upstream (a 50.0.1 é posterior à 42.0.8).
- Sem wheel Android em nenhuma versão no índice: `pydantic-core` (dependência de `pydantic==2.13.4`, núcleo do agente) e `resvg-py==0.4.0`. Isso bloqueia 3.13 e 3.14.
- Firecrawl-anydoc: wheels só para macOS/manylinux/musllinux/Windows e sdist. Não há tag Android.
- Conclusão: nenhuma das duas versões de Python fecha o build com o índice atual do Chaquopy. Próximos passos viáveis: compilar `pydantic-core` (Rust, cross-compile para Android) e as wheels nativas com a toolchain do Chaquopy, ou pedir ao Chaquopy as wheels cp314/android.

## Resultado: APK debug montado com wheels Android locais (2026-10-18)
- `./gradlew :app:assembleDebug` (teste em cópia isolada, fora do repositório): BUILD SUCCESSFUL, `arm64-v8a` e `x86_64`.
- Gate de 16 KB (`tools/check_elf_16kb.py`): `checked=20 failed=0`.
- Módulos nativos empacotados nos dois ABIs (`requirements-<abi>.imy`): pydantic-core, cryptography (Rust, sem cffi), resvg-py, psutil, Pillow (sem JPEG/FreeType/TIFF/LCMS/WebP), httptools, jiter, watchfiles.
- Wheels compilados com NDK 27 via cargo/setup.py cruzados, tag `cp314-cp314-android_24_<abi>`. Pacotes abi3 foram reetiquetados como `cp314-cp314` porque o índice do Chaquopy não publica tags abi3.
- Como montar: `app/wheels-local/` (não versionado) + `options("--find-links", "wheels-local")` no bloco `pip { }` do `app/build.gradle.kts`. Chaquopy ignora `--find-links` dentro de `requirements-android.txt` na passagem do x86_64.
- Exclusões do APK: `firecrawl-anydoc` (leitura de PDF indisponível) e `pillow-heif` (HEIC/HEIF indisponível). O núcleo importa ambos de forma opcional.
- **Não validado:** execução em aparelho ou emulador (sem KVM nesta sandbox). Os módulos nativos nunca foram importados num Android real. O passo 5 continua pendente.
- Pendências para tornar o build reproduzível a partir do git: publicar os wheels (release asset ou índice próprio) e decidir como o `build.gradle.kts` aponta para eles.

## Crash ao abrir o app: ImportError PyLong_Type (2026-10-18)
- Sintoma: `ImportError: dlopen failed: cannot locate symbol "PyLong_Type"` ao importar `pydantic_core`.
- Causa: as extensões cross-compiladas não declaravam `NEEDED libpython3.14.so`. Os módulos do próprio Chaquopy declaram, e o APK traz essa biblioteca em `lib/<abi>/`.
- Correção aplicada nos wheels: `patchelf --add-needed libpython3.14.so` em cada `.so` (ver o script de wheels fora do repositório). Qualquer módulo nativo recompilado precisa dessa dependência.
- O app mostra o erro de inicialização na tela (`ChatViewModel`, `HermesApplication`, `GatewayService`), em vez de fechar.

## Checklist: o que falta para rodar 100% (2026-10-19)
Feito e verificado nesta sandbox:
- `assembleDebug` passa (arm64-v8a e x86_64); gate de 16 KB passa (`checked=20 failed=0`).
- Resolução das dependências para Android só com wheels passa (pure do PyPI, nativos locais).
- Módulos nativos declaram `libpython3.14.so`; psutil trata `android` como Linux.
- Chave DeepSeek guardada criptografada (EncryptedSharedPreferences); sem chave o chat avisa.
- Erros de inicialização aparecem na tela.

Falta, em ordem:
1. Validação em aparelho (passo 5): abrir, "Pronto", resposta do modelo com chave real, diálogo de aprovação. Depende de aparelho e chave do usuário.
2. Import de todos os módulos do Hermes no runtime do Android (Fase 2). Só o startup foi testado.
3. Reprodutibilidade (Fase 1): os wheels nativos e os scripts de cross-build estão fora do repositório. Falta publicar os wheels e versionar os scripts.
4. Gate offline com `--no-index` (Fase 1): o pip ainda usa o PyPI para os pacotes puros.
5. Fase 5: login OAuth (Claude, ChatGPT) e escolha de provedor/fallback na UI. Hoje só há DeepSeek.
6. Fases 6 a 8 (segundo plano, ferramentas, release) não começaram.
7. Testes instrumentados e no emulador (plano 6.1): precisam de KVM, indisponível nesta sandbox.
8. Licença do Chaquopy antes de distribuir (plano 2.5).
