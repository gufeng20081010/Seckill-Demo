package org.seckill.service.impl;

import org.seckill.dao.SeckillDao;
import org.seckill.dao.SuccessKilledDao;
import org.seckill.dto.Exposer;
import org.seckill.dto.SeckillExecution;
import org.seckill.entity.Seckill;
import org.seckill.entity.SuccessKilled;
import org.seckill.enums.SeckillStateEnum;
import org.seckill.exception.RepeatKillException;
import org.seckill.exception.SeckillCloseException;
import org.seckill.exception.SeckillException;
import org.seckill.service.SeckillService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.DigestUtils;

import java.util.Date;
import java.util.List;

/**
 * Created by Jimmy on 2016-6-1.
 */
//@Component @Service @Repository @Controller

@Service
public class SeckillServiceImpl implements SeckillService {
    private Logger logger = LoggerFactory.getLogger(this.getClass());

    @Autowired
    private SeckillDao seckillDao;

    @Autowired
    private SuccessKilledDao successKilledDao;

    //md5盐值字符串，用于混淆md5
    private final String slat = "1354ASDF36684#$@%#^&*)4352130-235";

    @Override
    public List<Seckill> getSeckillList() {
        return seckillDao.queryAll(0, 4);
    }

    @Override
    public Seckill getById(long seckillId) {
        return seckillDao.queryById(seckillId);
    }

    @Override
    public Exposer exportSeckillUrl(long seckillId) {
        Seckill seckill = seckillDao.queryById(seckillId);

        if (seckill == null) {
            return new Exposer(false, seckillId);
        }

        Date startTime = seckill.getStartTime();
        Date endTime = seckill.getEndTime();
        Date now = new Date();

        if(startTime.getTime() > now.getTime() ||
                endTime.getTime() < now.getTime()){
            return new Exposer(false, now.getTime(), seckillId, startTime.getTime(), endTime.getTime());
        }

        String md5 = getMD5(seckillId);
        return new Exposer(true, md5, seckillId);
    }

    private String getMD5(long seckillId){
        String base = seckillId + "/" + slat;
        String md5 = DigestUtils.md5DigestAsHex(base.getBytes());
        return md5;
    }

    /**
    * 使用注解控制事务方法的优点：
     * 1.达成一致约定，明确标注注解事务方法的编程风格
     * 2.保证事务方法的执行时间尽可能短，不要穿插其他网络操作（rpc/http），解决办法（上层方法抽离处理）
     * 3.并不是所有方法都要事务，如单条修改操作
     *
     * 注意：
     * spring声明式事物，在抛出runtimeexception时进行rollback，所以要注意合理使用try catch，
     * 避免异常被catch到，事务未执行rollback。
     * ava异常主要是分编译期异常和运行期异常，运行期异常不需要手动try-catch，Spring的声明式事务只接受运行期异常回滚策略，
     * 当抛出非运行期异常时Spring不会帮我们做回滚的。
    * */
    @Override
    @Transactional
    public SeckillExecution executeSeckill(long seckillId, long userPhone, String md5) throws SeckillException, RepeatKillException, SeckillCloseException {
        if(md5 == null || !md5.equals(getMD5(seckillId))){
            throw new SeckillException("seckill data rewrite!");
        }
        //执行秒杀逻辑：减库存、记录购买行为
        Date now = new Date();

        try {
            //减库存
            int updateCount = seckillDao.reduceNumber(seckillId, now);
            if (updateCount <= 0){
                //没有更新数据，秒杀结束
                throw new SeckillCloseException("seckill is closed!");
            }else {
                int insertCount = successKilledDao.insertSuccessKilled(seckillId, userPhone);

                if (insertCount <= 0){
                    //重复秒杀
                    throw new RepeatKillException("seckill repeated!");
                }else{
                    //秒杀成功
                    SuccessKilled successKilled = successKilledDao.queryByIdWithSeckill(seckillId, userPhone);

                    return new SeckillExecution(seckillId, SeckillStateEnum.SUCCESS, successKilled);
                }
            }
        }catch (SeckillCloseException e){
            throw e;
        }catch (RepeatKillException e){
            throw e;
        }catch (Exception e){
            logger.error("error:{}", e.getMessage());
            //所有编译期异常，转化为运行时异常
            throw new SeckillException("seckill innere error:" + e.getMessage());
        }

    }
}
