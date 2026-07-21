package tsumugi.membership;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import tsumugi.initialsetup.InitialSetupService;
import tsumugi.initialsetup.model.InitialSetupRecord;
import tsumugi.membership.MembershipEvent.EventType;
import tsumugi.membership.store.MembershipRepository;
import tsumugi.withdrawal.WithdrawalDataChoice;
import tsumugi.withdrawal.WithdrawalState;
import tsumugi.withdrawal.model.WithdrawalRecord;
import tsumugi.withdrawal.store.WithdrawalRepository;

import java.util.logging.Logger;

/**
 * 入退室イベントの記録と、それに付随する処理（庭チャンネルの削除／
 * 記憶引継ぎ確認の要否判定）を一箇所にまとめるファサード。
 *
 * 責務を明確にするため、実際のチャンネル操作・状態遷移はInitialSetupServiceに委譲し、
 * ここでは「イベントを記録する」「withdrawal記録を見て次の処理を振り分ける」ことだけを行う。
 *
 * 運用は単一ギルド前提のため、判定はuserId基準（MembershipEvent側）で行いつつ、
 * withdrawal/initialsetup側の既存インタフェース（guildId必須）はそのまま呼び出す。
 */
public final class MembershipManager {

    private static final Logger logger = Logger.getLogger(MembershipManager.class.getName());

    private final MembershipRepository membershipRepository;
    private final WithdrawalRepository withdrawalRepository;
    private final InitialSetupService initialSetupService;

    public MembershipManager(MembershipRepository membershipRepository,
                              WithdrawalRepository withdrawalRepository,
                              InitialSetupService initialSetupService) {
        this.membershipRepository = membershipRepository;
        this.withdrawalRepository = withdrawalRepository;
        this.initialSetupService = initialSetupService;
    }

    /**
     * GuildMemberJoinEvent受信時に呼ぶ。
     * 退会時に「記名で保持」を選んでいたユーザーの再入室であれば引継ぎ確認フローへ、
     * それ以外は通常の初期設定フロー（名前入力）へ振り分ける。
     */
    public void recordEnter(Member member) {
        long userId = member.getIdLong();
        Guild guild = member.getGuild();

        try {
            membershipRepository.save(new MembershipEvent(userId, EventType.ENTER, false));
        } catch (RuntimeException e) {
            logger.warning("ENTERイベントの記録に失敗しました (userId=" + userId + "): " + e.getMessage());
        }

        WithdrawalRecord withdrawalRecord = withdrawalRepository.load(userId, guild.getIdLong());
        boolean eligibleForRejoin = withdrawalRecord.state == WithdrawalState.KICKED
                && withdrawalRecord.dataChoice == WithdrawalDataChoice.KEEP_NAMED;

        if (eligibleForRejoin) {
            InitialSetupRecord setupRecord = initialSetupService.getRecord(userId, guild.getIdLong());
            String previousDisplayName = setupRecord.displayName;
            if (previousDisplayName != null && !previousDisplayName.isBlank()) {
                initialSetupService.startRejoinConfirm(member, previousDisplayName);
                logger.info("記名保持での退会歴があるため、引継ぎ確認フローを開始しました: userId=" + userId);
                return;
            }
            logger.warning("記名保持での退会歴がありますが、以前の表示名が見つからないため通常フローにフォールバックします: userId=" + userId);
        }

        initialSetupService.startSetup(member);
    }

    /**
     * GuildMemberRemoveEvent受信時に呼ぶ。
     * withdrawalフロー経由の退会か単純な離脱/Kickかを問わず、庭チャンネルは即時削除する。
     */
    public void recordLeave(Guild guild, long userId) {
        boolean viaWithdrawal = isViaWithdrawal(guild, userId);

        try {
            membershipRepository.save(new MembershipEvent(userId, EventType.LEAVE, viaWithdrawal));
        } catch (RuntimeException e) {
            logger.warning("LEAVEイベントの記録に失敗しました (userId=" + userId + "): " + e.getMessage());
        }

        try {
            initialSetupService.tearDownGarden(guild, userId);
        } catch (RuntimeException e) {
            logger.warning("退室に伴う庭のクリーンアップに失敗しました (userId=" + userId + "): " + e.getMessage());
        }
    }

    /**
     * withdrawalレコードの状態から、この退室がwithdrawalフロー経由かどうかを推定する。
     * WithdrawalServiceはCONFIRMED→（Discord API的なkick）→KICKEDの順に遷移させるため、
     * GuildMemberRemoveEventがどちらのタイミングで届いても拾えるよう両方を対象にする。
     */
    private boolean isViaWithdrawal(Guild guild, long userId) {
        WithdrawalRecord record = withdrawalRepository.load(userId, guild.getIdLong());
        return record.state == WithdrawalState.CONFIRMED || record.state == WithdrawalState.KICKED;
    }
}
