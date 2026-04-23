# Alkagi

Paper 서버에서 즐길 수 있는 팀 대전형 알까기 미니게임 플러그인입니다.  
플레이어를 팀으로 나누고, 말을 배치한 뒤 턴제로 튕겨 상대 말을 장외로 밀어내는 방식으로 진행합니다.

## 지원 환경

- Minecraft / Paper API: `1.21.8`
- Java: `21`
- Build tool: `Gradle`

## 설치 방법

1. 플러그인을 다운받습니다..
2. `alkagi.jar`를 서버의 `plugins` 폴더에 넣습니다.
3. 서버를 실행해 기본 `config.yml`을 생성합니다.
4. 관리자 권한으로 경기장 관련 위치를 먼저 설정합니다.

플러그인을 설치한 뒤, 먼저 경기장 정보를 설정해야 정상적으로 게임을 시작할 수 있습니다.

권장 순서:

1. `/alkagi setboardpos1`
2. `/alkagi setboardpos2`
3. `/alkagi setlobby`
4. `/alkagi setspectator`
5. `/alkagi setblackplace`
6. `/alkagi setwhiteplace`
7. `/alkagi setturntime 30`
8. `/alkagi setpiecesize 2.35`
9. `/alkagi start <말개수> [플레이어수]`

예시:

```text
/alkagi start 10
/alkagi start 12 4
/alkagi forcestart 10 2
```

## 명령어

기본 명령어:

- `/alkagi status`
  현재 게임 상태, 참가 인원, 말 개수, 턴 시간, 말 크기 등을 확인합니다.
- `/alkagi start <count> [players]`
  경기장 설정이 완료된 상태에서 게임을 시작합니다.
- `/alkagi forcestart <count> [players]`
  일부 조건 검사를 건너뛰고 강제로 시작합니다.
- `/alkagi stop`
  진행 중인 게임을 중단합니다.
- `/alkagi reset`
  게임 상태를 초기화합니다.

경기장 설정 명령어:

- `/alkagi setboardpos1`
  보드 기준점 1을 현재 위치로 저장합니다.
- `/alkagi setboardpos2`
  보드 기준점 2를 현재 위치로 저장합니다.
- `/alkagi setlobby`
  로비 위치를 현재 위치로 저장합니다.
- `/alkagi setspectator`
  관전자 위치를 현재 위치로 저장합니다.
- `/alkagi setblackplace`
  흑팀 배치 위치를 현재 위치로 저장합니다.
- `/alkagi setwhiteplace`
  백팀 배치 위치를 현재 위치로 저장합니다.

게임 설정 명령어:

- `/alkagi setturntime <seconds>`
  턴 제한 시간을 초 단위로 설정합니다.
- `/alkagi setpiecesize <size>`
  말 크기를 설정합니다.
- `/alkagi setcontrolradius <value>`
  현재 구현상 직접 수동 설정 대신, 말 크기에 따라 자동 계산되도록 안내합니다.

## 설정 파일

기본 설정 파일은 `src/main/resources/config.yml` 기준으로 다음 항목을 사용합니다.

```yml
arena: {}

settings:
  piece-size: 2.35
  control-radius: piece-size * 2.5
  turn-time-seconds: 30

music:
  sound-key: "alkagi.ingame"
  volume: 0.1225
  loop-seconds: 120
```

주요 항목 설명:

- `arena`
  보드, 로비, 관전자, 팀 배치 위치 등 경기장 정보를 저장합니다.
- `settings.piece-size`
  말 크기를 설정합니다.
- `settings.control-radius`
  조작 반경 개념을 설명하는 설정값입니다. 현재는 말 크기 기준으로 계산되는 구조를 따릅니다.
- `settings.turn-time-seconds`
  턴 제한 시간을 설정합니다.
- `music.sound-key`
  게임 중 재생할 사운드 키입니다.
- `music.volume`
  사운드 볼륨입니다.
- `music.loop-seconds`
  반복 재생 주기입니다.

## 게임 진행 방식

게임은 대체로 다음 흐름으로 진행됩니다.

1. 플레이어가 참가합니다.
2. 팀별로 말을 정해진 위치에 배치합니다.
3. 게임이 시작되면 턴마다 현재 차례 플레이어가 자신의 말을 선택합니다.
4. 방향을 지정해 말을 발사합니다.
5. 상대 말을 보드 밖으로 밀어내며 승리를 노립니다.