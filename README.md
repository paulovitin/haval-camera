# Câmera Haval

Exemplo Android que mostra o vídeo das câmeras do Haval dentro de um APK. A captura usa o cliente Qualcomm AIS/QCarCam presente na central e um serviço com root iniciado pelo Shizuku.

[Baixar o APK de teste](https://github.com/paulovitin/haval-camera/releases) · [Integrar em outro app](docs/INTEGRATION.md) · [Funcionamento e diagnóstico](docs/CAMERA.md)

## O que funciona

Confirmamos na central testada a imagem composta de 1760 × 720: visão 360° à esquerda e câmera traseira à direita, com cores corretas e aproximadamente 15 quadros por segundo. O desenho do carro e as linhas de orientação já fazem parte desse fluxo. A implementação ainda não oferece cada lente separadamente.

O APK recebe os quadros em memória e desenha em uma `GLSurfaceView`. Durante a verificação, a pasta de dumps da câmera permaneceu vazia. O usuário confirmou na tela física os controles do APK junto da imagem.

Este primeiro release é experimental e específico para o firmware investigado. Consulte o [registro de validação](docs/VALIDATION.md) antes de tratar outra central como compatível. Ainda falta concluir os testes de fechar/reabrir, reinício e convivência com outros clientes de câmera.

## Usar o APK

Você precisa de uma central Android ARM64 com o cliente `/vendor/bin/qcarcam_test`, a entrada QCarCam de ID 4 descrita neste projeto e o **Shizuku iniciado como root (UID 0)**. Instalar o Shizuku sozinho não concede root; esta versão do app recusa o modo shell/ADB (UID 2000).

1. Instale o APK da página de releases pelo método aceito pela sua central.
2. Inicie o Shizuku com root, abra **Câmera Haval** e conceda a autorização solicitada.
3. Mantenha o app aberto para acompanhar o vídeo. **Conectar** tenta reiniciar a captura; **Fechar** encerra a Activity.

O código pede o encerramento da captura quando a Activity sai do primeiro plano. Não há inicialização automática no boot nem gravação de vídeo. Telnet, ADB e um computador foram ferramentas de investigação; o APK instalado não usa esses canais para transportar os quadros.

## Compilar

Use JDK 17, Bash, `curl`, `zip`, `unzip` e o Android SDK no macOS ou Linux x86_64. No Windows, use WSL. Instale os componentes com o SDK Manager:

```sh
sdkmanager 'platforms;android-36' 'build-tools;36.0.0' 'ndk;27.0.12077973'
export ANDROID_HOME=/caminho/para/android-sdk
bash build.sh
```

O script também aceita `ANDROID_SDK_ROOT`. No macOS, o caminho padrão é `$HOME/Library/Android/sdk`. O resultado fica em `build/haval-camera.apk`.

O build baixa os quatro módulos Shizuku API 13.1.5 do Maven Central e verifica seus SHA-256 em `dependencies.sha256`. Compila Java/AIDL e a biblioteca ARM64, empacota as licenças e verifica a assinatura e o alinhamento do APK. O projeto usa diretamente as ferramentas do SDK; não precisa de Gradle para gerar este exemplo.

Por padrão, o script gera uma chave de desenvolvimento em `build/debug.keystore`. Para usar uma chave existente, defina `CAMERA_KEYSTORE`, `CAMERA_KEY_ALIAS`, `CAMERA_STORE_PASS` e `CAMERA_KEY_PASS` no ambiente. Guarde a mesma chave para assinar atualizações; um build local ou do CI pode ter assinatura diferente da versão instalada. Nunca publique o keystore.

O workflow do GitHub também gera um APK como artifact. Ele verifica o build; não testa as câmeras do carro.

## Usar em outros aplicativos

O repositório oferece código-fonte e um app de referência. Siga o [guia de integração](docs/INTEGRATION.md) para copiar o serviço Shizuku, o AIDL, a biblioteca nativa e a renderização para seu projeto.

O app não publica um serviço de câmera aberto para outros pacotes, nem um endpoint de vídeo. Cada integração incorpora a captura, pede sua própria autorização no Shizuku e adapta as validações de caminhos ao seu `applicationId`. Isso mantém as operações root restritas aos arquivos esperados do app.

## Licença e dependências

O projeto mantém a [AGPL-3.0](LICENSE) escolhida para este repositório. Considere essa licença ao incorporar ou distribuir o código em outro aplicativo. A [Shizuku API](https://github.com/RikkaApps/Shizuku-API) usa a [licença MIT](LICENSES/Shizuku-API.txt).

O APK não inclui os binários Qualcomm nem bibliotecas extraídas da central. Ele usa os componentes que já existem no dispositivo. Este é um projeto independente, sem vínculo com GWM ou Qualcomm.
